package ascent.domgen

import zio.*

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

/** The generator entry point (see domgen/README.md to run).
  *
  * Reads the vendored webref JSON under `data/webref/` (relative to the project root), runs the pure [[Generator]]
  * pipeline, and writes the resulting Scala source files into the modules' `generated/` source trees.
  *
  * **No network**, no fetching: the vendored snapshot is committed at a pinned commit (see
  * `data/webref/PINNED_COMMIT.txt`), so output changes only when the generator or the vendored data changes.
  */
object Main extends ZIOAppDefault:

  /** Default project layout — relative to the current working directory (the project root when launched via sbt). */
  private val webrefDir       = Path.of("data/webref")
  private val domTypesGenDir  = Path.of("dom-types/src/main/scala/ascent/domtypes/generated")
  private val domFacadeGenDir = Path.of("dom-facade/src/main/scala/ascent/dom/generated")
  private val jsGenDir        = Path.of("js/src/main/scala/ascent/js/generated")
  private val cssGenDir       = Path.of("css/src/main/scala/ascent/css/generated")
  private val domCoreGenDir   = Path.of("dom-core/src/main/scala/ascent/domcore/generated")

  /** HTML elements whose attribute set is fully derived from IDL (per the plan's hybrid strictness: high-value elements
    * get typed per-element attrs, the rest reuse the common set). Bigger work — full per-element attribute grouping —
    * is a follow-up; for now this set seeds the global Attrs surface with their union.
    */
  private val strictElements: Set[String] =
    Set("input", "a", "img", "form", "label", "option", "select", "button", "textarea")

  def run: ZIO[Any, Throwable, Unit] =
    for
      _     <- Console.printLine("ascent-domgen: loading vendored webref data...")
      input <- loadVendored(webrefDir)
      _     <- Console.printLine(
        s"  ${input.elements.size} elements, ${input.events.size} events, ${input.idl.interfaces.size} IDL interfaces"
      )
      output <- Generator.run(input).mapError(e => new RuntimeException(s"Generator failed: $e"))
      _      <- Console.printLine(s"  ${output.files.size} HTML/event files generated")
      _      <- writeOutput(output)
      _      <- Console.printLine("ascent-domgen: loading vendored CSS specs...")
      cssCat <- loadCssCatalog(webrefDir.resolve("css"))
      _      <- Console.printLine(s"  ${cssCat.properties.size} CSS properties; ${cssCat.values.size} value defs")
      // Build the spec-wide value lookup for type-ref resolution (e.g. `<content-position>`).
      // Last-writer-wins on duplicates, matching Catalog's property semantics.
      valuesByName = cssCat.values.foldLeft(Map.empty[String, ascent.domgen.css.WebrefCss.ValueDef])((m, v) =>
        m.updated(v.name, v)
      )
      valueTraitsSrc = ascent.domgen.css.CssGenerator.generateValueTraits(valuesByName)
      _ <- writeUtf8(cssGenDir.resolve("StylesValueTraits.scala"), valueTraitsSrc)
      keywordValuesSrc = ascent.domgen.css.CssGenerator.generateKeywordValues(valuesByName, cssCat.properties)
      _ <- writeUtf8(cssGenDir.resolve("StylesKeywordValues.scala"), keywordValuesSrc)
      cssSrc = ascent.domgen.css.CssGenerator.generate(cssCat.properties, valuesByName)
      _ <- writeUtf8(cssGenDir.resolve("StylesGenerated.scala"), cssSrc)
      mediaSrc = ascent.domgen.css.MediaFeaturesGenerator.generateFromCatalog(cssCat)
      _ <- writeUtf8(cssGenDir.resolve("MediaFeatures.scala"), mediaSrc)
      containerSrc = ascent.domgen.css.ContainerFeaturesGenerator.generateFromCatalog(cssCat)
      _ <- writeUtf8(cssGenDir.resolve("ContainerFeatures.scala"), containerSrc)
      fontFaceSrc = ascent.domgen.css.FontFaceDescriptorsGenerator.generateFromCatalog(cssCat)
      _ <- writeUtf8(cssGenDir.resolve("FontFaceDescriptors.scala"), fontFaceSrc)
      pageSrc = ascent.domgen.css.PageDescriptorsGenerator.generateFromCatalog(cssCat)
      _ <- writeUtf8(cssGenDir.resolve("PageDescriptors.scala"), pageSrc)
      counterStyleSrc = ascent.domgen.css.CounterStyleDescriptorsGenerator.generateFromCatalog(cssCat)
      _ <- writeUtf8(cssGenDir.resolve("CounterStyleDescriptors.scala"), counterStyleSrc)
      // Typed element-tag selectors (`Tag.button`) come from the SAME element catalog as `Elements` — map each to its
      // keyword-safe Scala name + dom name.
      tagElements = input.elements.map(e => DefBuilder.scalaName(e.name) -> e.name)
      selectorSrc = ascent.domgen.css.SelectorGenerator.generate(cssCat, tagElements)
      _         <- writeUtf8(cssGenDir.resolve("PseudoSelectors.scala"), selectorSrc)
      _         <- Console.printLine("ascent-domgen: loading vendored aria-query data...")
      ariaProps <- loadAriaProps(Path.of("data/aria-query/ariaPropsMap.js"))
      _         <- Console.printLine(s"  ${ariaProps.size} ARIA properties")
      ariaSrc = ascent.domgen.aria.AriaRenderer.render(ariaProps)
      _ <- writeUtf8(domTypesGenDir.resolve("AriaAttrs.scala"), ariaSrc)
      _ <- Console.printLine("ascent-domgen: done.")
    yield ()

  /** Load + parse aria-query's `ariaPropsMap.js`, halting loudly on a parse failure (we don't want to silently emit an
    * empty ARIA catalog).
    */
  private def loadAriaProps(p: Path): Task[List[ascent.domgen.aria.AriaProperty]] =
    readUtf8(p).flatMap: text =>
      ascent.domgen.aria.AriaQueryParser.parseProperties(text) match
        case Right(props) => ZIO.succeed(props)
        case Left(err)    => ZIO.fail(new RuntimeException(s"aria-query parse failed: $err"))

  /** Load every `*.json` under `data/webref/css/`, parse, and aggregate into a [[ascent.domgen.css.WebrefCss.Catalog]].
    */
  private def loadCssCatalog(dir: Path): Task[ascent.domgen.css.WebrefCss.Catalog] =
    for
      files <- listJsonFiles(dir)
      specs <- ZIO.foreach(files): p =>
        readUtf8(p).flatMap(s => ascent.domgen.css.WebrefCss.parseSpec(s).mapError(toThrowable))
    yield ascent.domgen.css.WebrefCss.Catalog.fromSpecs(specs)

  private def writeUtf8(target: Path, body: String): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(target.getParent)
      Files.writeString(target, Format(target, body), StandardCharsets.UTF_8)
    } *> Console.printLine(s"  wrote $target")

  /** SVG element catalog files — the tag-name -> interface pairs, same shape as `elements/html.json`. Vendored
    * separately (see `data/webref/PINNED_COMMIT.txt`) because upstream webref splits SVG across three specs.
    */
  private val svgElementFiles: List[String] =
    List("elements/SVG2.json", "elements/svg-animations.json", "elements/svg-paths.json")

  /** Load every vendored input file and assemble a [[GeneratorInput]]. Each parse step has a typed
    * [[WebrefParseError]]; we widen to `Throwable` for the top-level app so failures become non-zero process exits with
    * a readable message.
    */
  private def loadVendored(root: Path): Task[GeneratorInput] =
    for
      elsJson <- readUtf8(root.resolve("elements/html.json"))
      els     <- Webref.parseElements(elsJson).mapError(toThrowable)
      svgEls  <- ZIO.foreach(svgElementFiles): f =>
        readUtf8(root.resolve(f)).flatMap(s => Webref.parseElements(s).mapError(toThrowable))
      evJson    <- readUtf8(root.resolve("events.json"))
      evs       <- Webref.parseEvents(evJson).mapError(toThrowable)
      allowText <- readUtf8(root.resolve("event-allowlist.txt"))
      idlFiles  <- listJsonFiles(root.resolve("idlparsed"))
      idls      <- ZIO.foreach(idlFiles): p =>
        readUtf8(p).flatMap(s => Webref.parseIdl(s).mapError(toThrowable))
    yield GeneratorInput(
      elements = els,
      events = evs,
      idl = Webref.mergeIdl(idls*),
      eventAllowlist = parseAllowlist(allowText),
      strictElements = strictElements,
      svgElements = svgEls.flatten,
    )

  private def toThrowable(e: WebrefParseError): Throwable = e

  /** Parse the allowlist file: one event-type per line, `#` starts a comment, blanks ignored. */
  private[domgen] def parseAllowlist(text: String): Set[String] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .toSet

  private def readUtf8(p: Path): Task[String] =
    ZIO.attemptBlocking(Files.readString(p, StandardCharsets.UTF_8))

  private def listJsonFiles(dir: Path): Task[List[Path]] =
    ZIO.attemptBlocking {
      val s = Files.list(dir)
      try s.iterator().asInstanceOf[java.util.Iterator[Path]].asScala.toList.filter(_.toString.endsWith(".json")).sorted
      finally s.close()
    }

  /** Write each generated file to the right tree, creating directories as needed. */
  private def writeOutput(out: GeneratorOutput): Task[Unit] =
    ZIO.foreachDiscard(out.files): (logical, body) =>
      val target = resolveOutputPath(logical)
      ZIO.attemptBlocking {
        Files.createDirectories(target.getParent)
        Files.writeString(target, Format(target, body), StandardCharsets.UTF_8)
      } *> Console.printLine(s"  wrote $target")

  private def resolveOutputPath(logical: String): Path = logical match
    case s"dom-types/$file"  => domTypesGenDir.resolve(file)
    case s"dom-facade/$file" => domFacadeGenDir.resolve(file)
    case s"js/$file"         => jsGenDir.resolve(file)
    case s"dom-core/$file"   => domCoreGenDir.resolve(file)
    case other               => Path.of(other)

  // Tiny iterator-to-Scala bridge so we don't pull in scala.jdk.CollectionConverters here.
  extension [A](it: java.util.Iterator[A])
    private def asScala: Iterator[A] = new Iterator[A]:
      def hasNext: Boolean = it.hasNext
      def next(): A        = it.next()

end Main
