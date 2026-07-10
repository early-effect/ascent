package ascent.domgen.css

import zio.*

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Diagnostic: dump info about CSS properties / value-defs from the vendored snapshot (see domgen/README.md to run).
  *
  * With no arguments, lists summary stats. With one or more property/value names, prints each one's grammar string,
  * value list, and originating spec(s). The first argument may be `selectors` or `atrules` to dump those catalogs
  * instead (optionally filtered by name). For debugging the CSS generator against the raw snapshot.
  */
object CssInspect extends ZIOAppDefault:

  override val bootstrap: ZLayer[Any, Any, Any] = Runtime.removeDefaultLoggers

  private val cssDir = Paths.get("data/webref/css")

  def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- getArgs
      _    <- args.toList match
        // First-arg gate so the existing property/value lookup still works as before.
        case "atrules" :: rest      => inspectAtrules(rest)
        case "selectors" :: rest    => inspectSelectors(rest)
        case "shorthand-parts" :: _ => inspectShorthandParts()
        case other                  => inspect(other)
    yield ()

  /** Diagnostic for the keyword-value-enum work: list every keyword `<type>` that appears as a COMPONENT of some
    * shorthand's any-order (`||`) / all-required (`&&`) grammar — the data-driven signal for "needs a composable
    * CssValue enum, not just a property-bound keyword trait". Prints, per such type, its keyword set and which
    * shorthand(s) reference it, and the total count so we can see the blast radius before generating.
    */
  private def inspectShorthandParts(): Task[Unit] =
    for
      files <- listJson(cssDir)
      raw   <- ZIO.foreach(files): p =>
        readUtf8(p).flatMap: text =>
          WebrefCss.parseSpec(text).mapError(e => new RuntimeException(s"$p: $e"))
      cat          = WebrefCss.Catalog.fromSpecs(raw)
      valuesByName = cat.values.foldLeft(Map.empty[String, WebrefCss.ValueDef])((m, v) => m.updated(v.name, v))
      excluded     = PropertyAnalyzer.knownTypeRefNames
      catalog      = ValueTraitCatalog.build(valuesByName, excluded)
      keywordTypes = catalog.map(_.typeName).toSet // bare names the generator turned into keyword traits
      // Every value-def OR property whose grammar is an any-order/all-required composition; collect its direct
      // TypeRef leaves that are keyword traits.
      componentUses = shorthandComponentUses(valuesByName, cat.properties, keywordTypes)
      parts         = componentUses.keys.toList.sorted
      _ <- Console.printLine(s"-- keyword <type>s used as shorthand components (|| / &&) --").orDie
      _ <- Console
        .printLine(s"  ${keywordTypes.size} keyword traits total; ${parts.size} are shorthand components")
        .orDie
      _ <- ZIO.foreachDiscard(parts): bare =>
        val kws  = catalog.find(_.typeName == bare).map(_.keywords.map(_.domName).mkString(" | ")).getOrElse("")
        val from = componentUses(bare).toList.sorted.mkString(", ")
        Console.printLine(s"  <$bare> = $kws   [in: $from]").orDie
    yield ()

  /** Map each keyword `<type>` (bare name in `keywordTypes`) to the set of shorthand grammars (value-def or property
    * names) that reference it as a direct any-order/all-required component.
    */
  private def shorthandComponentUses(
      valuesByName: Map[String, WebrefCss.ValueDef],
      properties: List[WebrefCss.Property],
      keywordTypes: Set[String],
  ): Map[String, Set[String]] =
    val out = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
    def record(part: String, from: String): Unit =
      out.getOrElseUpdate(part, scala.collection.mutable.Set.empty) += from
    // Direct any-order/all-required component TypeRefs of a grammar.
    def components(g: CssGrammar.Grammar): List[String] = g match
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.flatMap(directTypeRefs)
      case CssGrammar.AllInAnyOrder(alts)     => alts.flatMap(directTypeRefs)
      case CssGrammar.Group(inner)            => components(inner)
      case _                                  => Nil
    def directTypeRefs(g: CssGrammar.Grammar): List[String] = g match
      case CssGrammar.TypeRef(n)   => List(n.takeWhile(c => c != ' ' && c != '[').trim)
      case CssGrammar.Group(inner) => directTypeRefs(inner)
      case CssGrammar.OneOf(alts)  => alts.flatMap(directTypeRefs)
      case _                       => Nil
    def scan(gStr: String, from: String): Unit =
      CssGrammar.parse(gStr).foreach(ast => components(ast).filter(keywordTypes.contains).foreach(record(_, from)))
    valuesByName.values.foreach(v => v.value.foreach(scan(_, s"<${v.name.stripPrefix("<").stripSuffix(">")}>")))
    properties.foreach(p => p.value.foreach(scan(_, p.name)))
    out.map((k, v) => k -> v.toSet).toMap
  end shorthandComponentUses

  /** Dump every distinct selector (pseudo-class / pseudo-element / combinator) across the snapshot, with its grammar;
    * nested sub-selectors are indented. Pass names (`:hover` `::before`) to filter.
    */
  private def inspectSelectors(args: List[String]): Task[Unit] =
    for
      files <- listJson(cssDir)
      raw   <- ZIO.foreach(files): p =>
        readUtf8(p).flatMap: text =>
          WebrefCss.parseSpec(text).mapError(e => new RuntimeException(s"$p: $e")).map(p.getFileName.toString -> _)
      cat   = WebrefCss.Catalog.fromSpecs(raw.map(_._2))
      shown =
        if args.isEmpty then cat.selectors
        else cat.selectors.filter(s => args.contains(s.name))
      functional = cat.selectors.count(_.name.endsWith("()"))
      elements   = cat.selectors.count(_.name.startsWith("::"))
      _ <- Console.printLine(s"-- selectors across the snapshot --").orDie
      _ <- Console
        .printLine(
          s"  ${cat.selectors.size} distinct ($elements pseudo-elements, $functional functional, " +
            s"${cat.selectors.size - elements} pseudo-classes)"
        )
        .orDie
      _ <- ZIO.foreachDiscard(shown.sortBy(_.name)): s =>
        val grammar = s.value.filter(_ != s.name).fold("")(v => s"  =>  $v")
        for
          _ <- Console.printLine(s"  ${s.name}$grammar").orDie
          _ <- ZIO.foreachDiscard(s.children)(c => Console.printLine(s"      ${c.name}").orDie)
        yield ()
    yield ()

  /** Dump every distinct at-rule across the snapshot, with descriptor counts (and per-name descriptor names + grammars
    * if you pass specific at-rule names like `@media @font-face`).
    */
  private def inspectAtrules(args: List[String]): Task[Unit] =
    for
      files <- listJson(cssDir)
      raw   <- ZIO.foreach(files): p =>
        readUtf8(p).flatMap: text =>
          WebrefCss
            .parseSpec(text)
            .mapError(e => new RuntimeException(s"$p: $e"))
            .map(p.getFileName.toString -> _)
      // Group ALL atrules by name across all specs (a single at-rule can be split across
      // many partials — e.g. `@media` features defined in mediaqueries-5.json AND css-conditional).
      bySpec = raw.flatMap { case (f, s) => s.atrules.map(r => (f, r)) }
      byName = bySpec.groupBy(_._2.name)
      sorted = byName.toList.sortBy(_._1)
      _ <- Console.printLine(s"-- at-rules across the snapshot --").orDie
      _ <- Console.printLine(s"  ${sorted.size} distinct at-rule names").orDie
      _ <- ZIO.foreachDiscard(sorted): (name, hits) =>
        val descriptors = hits.flatMap(_._2.descriptors).distinctBy(_.name)
        val specs       = hits.map(_._1).distinct
        for _ <- Console
            .printLine(s"  $name (${descriptors.size} unique descriptors across ${specs.size} spec files)")
            .orDie
        yield ()
      // If specific at-rule names were passed, dump their descriptor lists.
      _ <- ZIO.foreachDiscard(args): name =>
        val asked = if name.startsWith("@") then name else "@" + name
        byName.get(asked) match
          case scala.None =>
            Console.printLine(s"\n  (no at-rule named $asked)").orDie
          case Some(hits) =>
            val descriptors = hits.flatMap(_._2.descriptors).distinctBy(_.name).sortBy(_.name)
            for
              _ <- Console.printLine(s"\n## $asked").orDie
              _ <- Console.printLine(s"  defined across: ${hits.map(_._1).distinct.mkString(", ")}").orDie
              _ <- ZIO.foreachDiscard(descriptors): d =>
                Console.printLine(s"    - ${d.name}: ${d.value.getOrElse("(no grammar)")}").orDie
            yield ()
        end match
    yield ()

  private def inspect(args: List[String]): Task[Unit] =
    for
      files <- listJson(cssDir)
      raw   <- ZIO.foreach(files): p =>
        readUtf8(p).flatMap: text =>
          WebrefCss
            .parseSpec(text)
            .mapError(e => new RuntimeException(s"$p: $e"))
            .map(p.getFileName.toString -> _)
      bySpec = raw // List[(specFile, Spec)]
      cat    = WebrefCss.Catalog.fromSpecs(bySpec.map(_._2))
      _ <- summary(bySpec, cat)
      _ <- ZIO.foreachDiscard(args)(name => report(name, bySpec, cat))
    yield ()

  private def summary(bySpec: List[(String, WebrefCss.Spec)], cat: WebrefCss.Catalog): Task[Unit] =
    for
      _ <- Console.printLine(s"-- vendored CSS snapshot --").orDie
      _ <- Console.printLine(s"  ${bySpec.size} spec files").orDie
      _ <- Console.printLine(s"  ${cat.properties.size} unique properties (after merge)").orDie
      _ <- Console.printLine(s"  ${cat.atrules.size} atrules").orDie
      _ <- Console.printLine(s"  ${cat.values.size} value defs").orDie
      _ <- Console
        .printLine(
          s"  raw counts: ${bySpec.map(_._2.properties.size).sum} props, ${bySpec.map(_._2.values.size).sum} values"
        )
        .orDie
    yield ()

  private def report(name: String, bySpec: List[(String, WebrefCss.Spec)], cat: WebrefCss.Catalog): Task[Unit] =
    for
      _ <- Console.printLine(s"\n## $name").orDie
      // Property?
      _ <- bySpec.flatMap { case (f, s) =>
        s.properties.filter(_.name == name).map(p => f -> p)
      } match
        case Nil =>
          Console.printLine(s"  (not found as a property)").orDie
        case hits =>
          ZIO.foreachDiscard(hits): (f, p) =>
            for
              _ <- Console.printLine(s"  property in $f").orDie
              _ <- Console.printLine(s"    value:     ${p.value}").orDie
              _ <- Console.printLine(s"    initial:   ${p.initial}").orDie
              _ <- Console.printLine(s"    inherited: ${p.inherited}").orDie
              _ <- Console
                .printLine(s"    values:    ${p.values.map(v => s"${v.name}(${v.kind})").mkString(", ")}")
                .orDie
            yield ()
      // Value def?
      angleName = if name.startsWith("<") then name else s"<$name>"
      _ <- bySpec.flatMap { case (f, s) =>
        findValue(s.values, angleName).map(v => f -> v)
      } match
        case Nil =>
          Console.printLine(s"  (not found as $angleName value def)").orDie
        case hits =>
          ZIO.foreachDiscard(hits): (f, v) =>
            for
              _ <- Console.printLine(s"  value-def in $f").orDie
              _ <- Console.printLine(s"    name:     ${v.name}").orDie
              _ <- Console.printLine(s"    type:     ${v.kind}").orDie
              _ <- Console.printLine(s"    value:    ${v.value}").orDie
              _ <- Console
                .printLine(s"    children: ${v.children.map(c => s"${c.name}(${c.kind})").mkString(", ")}")
                .orDie
            yield ()
      // What the generator would emit
      _ <- cat.properties.find(_.name == name) match
        case Some(p) =>
          val src = CssGenerator.generate(List(p), valuesByName = cat.values.map(v => v.name -> v).toMap)
          // Skip the file header — print just the property's object.
          val lines = src.linesIterator.toList
          val start = lines.indexWhere(_.trim.startsWith("object"))
          val tail  = if start >= 0 then lines.drop(start) else Nil
          Console
            .printLine(
              s"  generator output:\n${tail.takeWhile(l => !l.trim.startsWith("object ") || l.trim.startsWith(s"object ${camelCase(p.name)}")).mkString("\n")}"
            )
            .orDie
        case scala.None => ZIO.unit
    yield ()

  private def findValue(values: List[WebrefCss.ValueDef], name: String): List[WebrefCss.ValueDef] =
    values.flatMap { v =>
      val self = if v.name == name then List(v) else Nil
      self ++ findValue(v.children, name)
    }

  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString

  private def listJson(dir: Path): Task[List[Path]] =
    ZIO.attemptBlocking {
      val s = Files.list(dir)
      try s.iterator().asScala.toList.filter(_.toString.endsWith(".json")).sorted
      finally s.close()
    }

  private def readUtf8(p: Path): Task[String] =
    ZIO.attemptBlocking(Files.readString(p, StandardCharsets.UTF_8))
end CssInspect
