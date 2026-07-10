package ascent.domgen.css

/** Glue: take a list of [[WebrefCss.Property]] entries (from one or more parsed webref CSS specs) and produce the
  * generated `StylesGenerated.scala` source string ready to commit.
  *
  * Pipeline:
  *   1. Camel-case the dom name to a Scala identifier.
  *   2. Parse the property's `value` grammar string (if present) with [[CssGrammar]].
  *   3. Reduce the grammar to a [[PropertyShape]] via [[PropertyAnalyzer]].
  *   4. Render via [[CssRenderer]].
  *
  * Failure modes the generator handles WITHOUT crashing:
  *   - missing `value:` field → bare object (no traits, no keywords)
  *   - parser failure on `value:` string → bare object PLUS a `// could not parse: ...` comment so reviewers see the
  *     regression.
  *   - duplicate Scala name → last-writer-wins (matches `WebrefCss.Catalog`)
  */
object CssGenerator:

  /** Properties whose generated form is OVERRIDDEN by a hand-written variant in `ascent.css.StylesOverrides`. The
    * generator skips these so the trait-mixin chain (`Styles extends StylesGenerated, StylesOverrides`) sees only the
    * hand-written ones.
    *
    * Names are CSS dom names (with hyphens), matching what webref ships.
    */
  val overriddenProperties: Set[String] = Set(
    "margin",      // hand-written 2- and 4-value typed-length shorthand `apply` overloads
    "padding",     // same
    "font-weight", // hand-written `apply(Int)` for 100/200/.../900
    "flex",        // hand-written typed `apply(grow, shrink, basis)` shorthand
  )

  /** Produce the full source body, dropping properties in [[overriddenProperties]] so the hand-written overrides win at
    * the use-site. The optional `valuesByName` map is the spec-wide value lookup table used to resolve `<type-ref>`s
    * (e.g. `<content-position>`) to their own keyword sets. Property-refs (`<'text-decoration-line'>`) are resolved
    * against the property list itself.
    */
  def generate(
      props: List[WebrefCss.Property],
      valuesByName: Map[String, WebrefCss.ValueDef] = Map.empty,
  ): String =
    val filtered    = props.filterNot(p => overriddenProperties.contains(p.name))
    val propsByName = props.map(p => p.name -> p).toMap
    val catalog     = valueTraitCatalog(valuesByName)
    CssRenderer.renderTrait(analyze(filtered, valuesByName, propsByName, catalog), catalog)

  /** Render the value-type-trait catalog file (`StylesValueTraits.scala`) — one trait per shared keyword-bearing CSS
    * `<type>`. Written alongside `StylesGenerated.scala`.
    */
  def generateValueTraits(valuesByName: Map[String, WebrefCss.ValueDef]): String =
    CssRenderer.renderValueTraits(valueTraitCatalog(valuesByName))

  /** Render the keyword-value-enum file (`StylesKeywordValues.scala`) — one `enum … extends CssValue` per
    * shorthand-component keyword `<type>`, the composable-value sibling of the property-bound keyword traits.
    */
  def generateKeywordValues(
      valuesByName: Map[String, WebrefCss.ValueDef],
      properties: List[WebrefCss.Property],
  ): String =
    CssRenderer.renderKeywordValues(KeywordValueCatalog.build(valuesByName, properties, excludedFromValueTraits))

  /** Bare type names already owned by a hand-written foundation/ADT trait (via [[PropertyAnalyzer.typeRefTraits]]) — a
    * generated keyword trait must not also claim them, else a property would mix two traits for one `<type>`.
    */
  private def excludedFromValueTraits: Set[String] = PropertyAnalyzer.knownTypeRefNames

  /** Build the value-type-trait catalog from the spec-wide value lookup. Memo-free; called once per `generate`. */
  private[css] def valueTraitCatalog(valuesByName: Map[String, WebrefCss.ValueDef]): List[ValueTrait] =
    ValueTraitCatalog.build(valuesByName, excludedFromValueTraits)

  /** Materialize a list of [[PropertyDef]]s, preserving input order, last-writer-wins on Scala-name collision. The
    * Scala name is the camel-cased dom name; webref's dom names don't realistically collide after camel-casing, but the
    * rule is locked here for safety.
    */
  private[css] def analyze(
      props: List[WebrefCss.Property],
      valuesByName: Map[String, WebrefCss.ValueDef] = Map.empty,
      propsByName: Map[String, WebrefCss.Property] = Map.empty,
      catalog: List[ValueTrait] = Nil,
  ): List[PropertyDef] =
    // Filesystem-case-collision guard: `strokeDashcorner` (svg-strokes) and
    // `strokeDashCorner` (fill-stroke) differ only in case; on case-insensitive
    // filesystems Scala.js classfile output collides. The fix only kicks in when two
    // DIFFERENT dom names share a case-insensitive Scala name. Repeats of the same dom
    // name (e.g. webref re-declaring a property in two specs) flow through last-writer-
    // wins on the camel-cased name, just like Catalog.fromSpecs does.
    val seenLowercase = scala.collection.mutable.HashMap.empty[String, String]
    val byScalaName   = scala.collection.mutable.LinkedHashMap.empty[String, PropertyDef]
    props.foreach { p =>
      val baseName  = camelCase(p.name)
      val lc        = baseName.toLowerCase
      val scalaName = seenLowercase.get(lc) match
        case scala.None                         => seenLowercase(lc) = p.name; baseName
        case Some(prevDom) if prevDom == p.name => baseName
        case Some(_)                            => s"${baseName}_${p.name.replace('-', '_')}"
      byScalaName(scalaName) = toPropertyDef(p, valuesByName, propsByName, catalog).copy(scalaName = scalaName)
    }
    byScalaName.values.toList
  end analyze

  /** Extract simple keyword literals from a property's `values:` listing. We surface every top-level entry with
    * `kind == "value"` whose `name` is a CSS-keyword-shaped identifier (no angle brackets, no parens, no spaces). These
    * flow into the analyzer as extra top-level keyword alternatives — covering the spec-listed in-context keywords for
    * a property even when the main grammar string buries them inside `<type-ref>`s.
    */
  private def extractValueKeywords(values: List[WebrefCss.ValueDef]): List[String] =
    values.collect {
      case v if v.kind == "value" && v.name.matches("[a-zA-Z][a-zA-Z0-9-]*") => v.name
    }

  /** Walk the grammar surfacing every keyword/type-ref reachable through the "branch-like" structures (`OneOf`,
    * `AnyOrderOneOrMore`, `AllInAnyOrder`, `Group`, `Multiplied`). Stops at `Sequence` and `FunctionCall` — those
    * produce ordered tuples, not alternatives.
    *
    * The walk lets us harvest in-context keywords from grammars like
    * `<overflow-position>? [ <content-position> | left | right ]`, where the typed-keyword branches are nested inside
    * groups + optional multipliers.
    */
  private def collectKeywordsAndTypeRefs(g: CssGrammar.Grammar): List[CssGrammar.Grammar] =
    g match
      case CssGrammar.OneOf(alts)             => alts.flatMap(collectKeywordsAndTypeRefs)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.flatMap(collectKeywordsAndTypeRefs)
      case CssGrammar.AllInAnyOrder(alts)     => alts.flatMap(collectKeywordsAndTypeRefs)
      case CssGrammar.Sequence(items)         => items.flatMap(collectKeywordsAndTypeRefs)
      case CssGrammar.Group(inner)            => collectKeywordsAndTypeRefs(inner)
      case CssGrammar.Multiplied(operand, _)  => collectKeywordsAndTypeRefs(operand)
      case CssGrammar.Keyword(_)              => List(g)
      case CssGrammar.TypeRef(_)              => List(g)
      case _                                  => Nil

  /** Resolve top-level `<type-ref>`s (anywhere reachable through OneOf/Group/Multiplied/ Sequence) against the
    * spec-wide value lookup, harvesting the resolved type's `values:` keywords AND the keywords reachable through its
    * `value:` grammar string. One level deep — no transitive walks — to keep the keyword fan-out predictable.
    */
  private def resolveTypeRefKeywords(
      g: CssGrammar.Grammar,
      valuesByName: Map[String, WebrefCss.ValueDef],
  ): List[String] =
    collectKeywordsAndTypeRefs(g).flatMap {
      case CssGrammar.TypeRef(name) =>
        val baseName  = name.takeWhile(c => c != ' ' && c != '[').trim
        val angleName = s"<$baseName>"
        valuesByName.get(angleName) match
          case scala.None => Nil
          case Some(v)    =>
            // Direct keyword children (e.g. `<content-position>` lists `center`, `start`, ...)
            val direct = v.children.collect {
              case child if child.kind == "value" && child.name.matches("[a-zA-Z][a-zA-Z0-9-]*") =>
                child.name
            }
            // Resolve via the type's own grammar string too — chains like `<opacity-value>` →
            // `<number> | <percentage>`. We recursively walk one level: parse the inner
            // grammar, surface direct keywords from it.
            val viaGrammar = v.value.toList.flatMap { gStr =>
              CssGrammar.parse(gStr) match
                case Right(innerAst) =>
                  collectKeywordsAndTypeRefs(innerAst).collect { case CssGrammar.Keyword(n) =>
                    n
                  }
                case Left(_) => Nil
            }
            direct ++ viaGrammar
        end match
      case _ => Nil
    }

  /** Resolve top-level `<type-ref>`s into the FOUNDATION TRAITS implied by their underlying grammar (e.g.
    * `<opacity-value>` is `<number> | <percentage>` → contributes `Numeric` + `Percent`). Walks one level deep through
    * the spec-wide values lookup.
    */
  private def resolveTypeRefTraits(
      g: CssGrammar.Grammar,
      valuesByName: Map[String, WebrefCss.ValueDef],
  ): Set[String] =
    collectKeywordsAndTypeRefs(g).flatMap {
      case CssGrammar.TypeRef(name) =>
        val baseName  = name.takeWhile(c => c != ' ' && c != '[').trim
        val angleName = s"<$baseName>"
        valuesByName.get(angleName) match
          case scala.None => Set.empty[String]
          case Some(v)    =>
            v.value.toList.flatMap { gStr =>
              CssGrammar.parse(gStr) match
                case Right(innerAst) =>
                  // Surface the analyzer's TRAIT inferences for the inner grammar.
                  PropertyAnalyzer.analyze(innerAst).traits
                case Left(_) => Nil
            }.toSet
        end match
      case _ => Set.empty
    }.toSet

  /** Resolve any top-level `PropertyRef` in the grammar against the property catalog, harvesting the referenced
    * property's keyword/trait set. One level deep — no transitive walks. Used for shorthand grammars like
    * `text-decoration: <'text-decoration-line'> || ...`.
    */
  private def resolvePropertyRefShape(
      g: CssGrammar.Grammar,
      valuesByName: Map[String, WebrefCss.ValueDef],
      propsByName: Map[String, WebrefCss.Property],
  ): (List[String], Set[String]) =
    val keywords = scala.collection.mutable.ArrayBuffer.empty[String]
    val traits   = scala.collection.mutable.HashSet.empty[String]
    collectKeywordsAndTypeRefs(g).foreach {
      case CssGrammar.TypeRef(_) => ()
      case CssGrammar.Keyword(_) => ()
      case _                     => ()
    }
    // Walk the grammar tree separately to find PropertyRefs.
    def walkForPropRefs(g: CssGrammar.Grammar): Unit = g match
      case CssGrammar.OneOf(alts)             => alts.foreach(walkForPropRefs)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.foreach(walkForPropRefs)
      case CssGrammar.AllInAnyOrder(alts)     => alts.foreach(walkForPropRefs)
      case CssGrammar.Sequence(items)         => items.foreach(walkForPropRefs)
      case CssGrammar.Group(inner)            => walkForPropRefs(inner)
      case CssGrammar.Multiplied(operand, _)  => walkForPropRefs(operand)
      case CssGrammar.PropertyRef(name)       =>
        propsByName.get(name).foreach { ref =>
          // Recursively analyze the referenced property's grammar (one level deep).
          ref.value.foreach { gStr =>
            CssGrammar.parse(gStr) match
              case Right(refAst) =>
                val refShape = PropertyAnalyzer.analyze(
                  refAst,
                  extractValueKeywords(ref.values) ++ resolveTypeRefKeywords(refAst, valuesByName),
                  resolveTypeRefTraits(refAst, valuesByName),
                )
                refShape.traits.foreach(traits += _)
                refShape.keywords.foreach(km => keywords += km.domName)
              case Left(_) => ()
          }
        }
      case _ => ()
    walkForPropRefs(g)
    (keywords.toList, traits.toSet)
  end resolvePropertyRefShape

  private def toPropertyDef(
      p: WebrefCss.Property,
      valuesByName: Map[String, WebrefCss.ValueDef],
      propsByName: Map[String, WebrefCss.Property],
      catalog: List[ValueTrait],
  ): PropertyDef =
    val scalaName        = camelCase(p.name)
    val ownValueKeywords = extractValueKeywords(p.values)
    val doc              = synthDoc(p)
    // bare-type-name -> generated trait, and trait -> the keyword scala-names it owns (so the property
    // never re-declares a keyword its mixed-in value-trait already provides).
    val byType  = catalog.map(t => t.typeName -> t).toMap
    val byTrait = catalog.map(t => t.traitName -> t).toMap
    p.value match
      case scala.None =>
        // No grammar string; still surface any spec-listed keyword values (rare but observed).
        val shape =
          if ownValueKeywords.isEmpty then PropertyShape(Nil, Nil)
          else PropertyAnalyzer.analyze(CssGrammar.Sequence(Nil), ownValueKeywords)
        PropertyDef(scalaName, p.name, shape, doc = doc)
      case Some(grammar) =>
        CssGrammar.parse(grammar) match
          case Right(ast) =>
            val resolvedKeywords            = resolveTypeRefKeywords(ast, valuesByName)
            val resolvedTraits              = resolveTypeRefTraits(ast, valuesByName)
            val (propRefKws, propRefTraits) = resolvePropertyRefShape(ast, valuesByName, propsByName)
            // Value-type traits: every `<type>` the grammar references (directly or via a one-level
            // property-ref) that the catalog turned into a keyword trait. We mix the trait; its keywords
            // are owned by the trait, so they get filtered out of the property's inline keyword list.
            val valueTraits       = resolveValueTraits(ast, propsByName, byType)
            val ownedKeywordNames = valueTraits.flatMap(t => byTrait.get(t).toList.flatMap(_.keywords.map(_.scalaName)))
            val extras            = ownValueKeywords ++ resolvedKeywords ++ propRefKws
            // A border-style shorthand grammar (`<line-width> || <line-style> || <color>`, directly or via a
            // `<'border-top-*'>` ref) gets the hand-written `Borderish` mixin so `border(Border(...))` works.
            val borderTrait =
              if isBorderShorthand(ast, propsByName, valuesByName) then Set("Borderish") else Set.empty[String]
            // A 1–4 value `<length-percentage>` box (`inset`, `border-width`, `border-radius`, `scroll-margin`, logical
            // `margin-block`, …) gets the hand-written `LengthBox` mixin (typed `apply(Length, …)` multi-value forms).
            val lengthBoxTrait =
              if isLengthBox(ast, propsByName, valuesByName) then Set("LengthBox") else Set.empty[String]
            val allTraits = resolvedTraits ++ propRefTraits ++ valueTraits ++ borderTrait ++ lengthBoxTrait
            PropertyDef(
              scalaName,
              p.name,
              PropertyAnalyzer.analyze(ast, extras, allTraits, ownedKeywordNames.toSet),
              doc = doc,
            )
          case Left(err) =>
            // Grammar didn't parse, but the property's own `values:` listing may still have
            // usable in-context keywords. Surface those rather than emitting a bare object.
            val shape =
              if ownValueKeywords.isEmpty then PropertyShape(Nil, Nil)
              else PropertyAnalyzer.analyze(CssGrammar.Sequence(Nil), ownValueKeywords)
            PropertyDef(scalaName, p.name, shape, parseError = Some(err), doc = doc)
    end match
  end toPropertyDef

  /** Is this property a border-style shorthand — the `<line-width> || <line-style> || <color>` shape behind
    * `border`/`border-top`/…, the logical `border-block`/`-inline`(`-start`/`-end`), `outline`, and `column-rule`?
    * Detected by the grammar referencing BOTH a line-style type (`<line-style>` / `<outline-line-style>` / any
    * `*line-style*`) AND `<color>` anywhere reachable — expanding `<'property'>` refs TRANSITIVELY (bounded, cycle-
    * guarded) so the multi-level logical shorthands (`border-block` → `<'border-block-start'>` → `… || <line-style> ||
    * <color>`) and `outline`/`column-rule` (whose `<'*-style'>`/`<'*-color'>` refs expand to `<outline-line-style>` /
    * `<color>`) are all caught. Such properties get the hand-written `Borderish` mixin (`border(Border(...))`).
    */
  private def isBorderShorthand(
      g: CssGrammar.Grammar,
      propsByName: Map[String, WebrefCss.Property],
      valuesByName: Map[String, WebrefCss.ValueDef],
  ): Boolean =
    val typeRefs                                       = scala.collection.mutable.Set.empty[String]
    val visited                                        = scala.collection.mutable.Set.empty[String]
    def walk(gr: CssGrammar.Grammar, depth: Int): Unit =
      if depth <= 6 then
        gr match
          case CssGrammar.OneOf(a)             => a.foreach(walk(_, depth))
          case CssGrammar.AnyOrderOneOrMore(a) => a.foreach(walk(_, depth))
          case CssGrammar.AllInAnyOrder(a)     => a.foreach(walk(_, depth))
          case CssGrammar.Sequence(a)          => a.foreach(walk(_, depth))
          case CssGrammar.Group(inner)         => walk(inner, depth)
          case CssGrammar.Multiplied(op, _)    => walk(op, depth)
          case CssGrammar.TypeRef(name)        =>
            val bare = name.takeWhile(c => c != ' ' && c != '[').trim
            typeRefs += bare
            // Descend into a referenced `<type>`'s own grammar (e.g. `<gap-rule-list>` → … → line-style + color).
            if !visited.contains(bare) then
              visited += bare
              valuesByName
                .get(s"<$bare>")
                .flatMap(_.value)
                .foreach(s => CssGrammar.parse(s).foreach(walk(_, depth + 1)))
          case CssGrammar.PropertyRef(name) =>
            if !visited.contains(s"'$name'") then
              visited += s"'$name'"
              propsByName.get(name).flatMap(_.value).foreach(s => CssGrammar.parse(s).foreach(walk(_, depth + 1)))
          case _ => ()
    walk(g, 0)
    val hasStyle = typeRefs.exists(t => t == "line-style" || t.endsWith("line-style"))
    hasStyle && typeRefs.contains("color")
  end isBorderShorthand

  /** Is this property a 1–4 value `<length-percentage>` box shorthand — `<top>{1,4}` (`inset`), `<length-percentage>
    * {1,4}` (`border-radius`), `<'border-top-width'>{1,4}` (`border-width`), `<'margin-top'>{1,2}` (`margin-block`),
    * `[ auto | <length-percentage> ]{1,4}` (`scroll-padding`)? Detected by a TOP-LEVEL `Multiplied` whose multiplier
    * admits ≥2 occurrences and whose operand resolves (transitively through `<type>` / `<'property'>` refs) to a
    * length/percentage. Such properties get the hand-written `LengthBox` mixin (`inset(0, 14.px, 0, 20.px)`).
    */
  private def isLengthBox(
      g: CssGrammar.Grammar,
      propsByName: Map[String, WebrefCss.Property],
      valuesByName: Map[String, WebrefCss.ValueDef],
  ): Boolean =
    // A length BOX is SPACE-separated 1–4 values (`{1,4}` / `{1,2}`). A comma-list (`#`) is a LAYER list (background
    // layers, shadow list) — NOT a box — so it's excluded; those are handled by their own value-ADT traits.
    def admitsMultiple(m: CssGrammar.Multiplier): Boolean = m match
      case CssGrammar.Multiplier.Range(_, max) => max.forall(_ >= 2)
      case _                                   => false
    // Does this operand grammar resolve to a length/percentage (transitively through type / property refs)? Walks the
    // grammar tree directly (NOT via collectKeywordsAndTypeRefs, which drops PropertyRef nodes — `inset` is
    // `<'top'>{1,4}`, a PropertyRef operand).
    def lengthBearing(grammar: CssGrammar.Grammar, depth: Int, visited: Set[String]): Boolean =
      if depth > 6 then false
      else
        grammar match
          case CssGrammar.TypeRef(name) =>
            val bare = name.takeWhile(c => c != ' ' && c != '[').trim
            bare == "length" || bare == "length-percentage" || bare == "percentage" ||
            (!visited.contains(bare) && valuesByName
              .get(s"<$bare>")
              .flatMap(_.value)
              .exists(s => CssGrammar.parse(s).toOption.exists(lengthBearing(_, depth + 1, visited + bare))))
          case CssGrammar.PropertyRef(name) =>
            !visited.contains(s"'$name'") && propsByName
              .get(name)
              .flatMap(_.value)
              .exists(s => CssGrammar.parse(s).toOption.exists(lengthBearing(_, depth + 1, visited + s"'$name'")))
          case CssGrammar.OneOf(a)             => a.exists(lengthBearing(_, depth, visited))
          case CssGrammar.AnyOrderOneOrMore(a) => a.exists(lengthBearing(_, depth, visited))
          case CssGrammar.AllInAnyOrder(a)     => a.exists(lengthBearing(_, depth, visited))
          case CssGrammar.Sequence(a)          => a.exists(lengthBearing(_, depth, visited))
          case CssGrammar.Group(inner)         => lengthBearing(inner, depth, visited)
          case CssGrammar.Multiplied(op, _)    => lengthBearing(op, depth, visited)
          case _                               => false
    // The qualifying shape is a `Multiplied(lengthBearing, ≥2)` at the top level, or as the FIRST item of a top-level
    // Sequence (`border-radius` = `<lp>{1,4} [ / <lp>{1,4} ]?`). Unwrap a single-item Group too.
    def check(grammar: CssGrammar.Grammar): Boolean = grammar match
      case CssGrammar.Multiplied(op, m)    => admitsMultiple(m) && lengthBearing(op, 0, Set.empty)
      case CssGrammar.Sequence(first :: _) => check(first)
      case CssGrammar.Group(inner)         => check(inner)
      case _                               => false
    check(g)
  end isLengthBox

  /** Which value-type traits (from the catalog) a property's grammar references — by scanning every `<type-ref>`
    * reachable through branch-like structures, plus one level into any `<'property'>` ref's own grammar. A type whose
    * bare name is a catalog entry contributes that trait; the trait owns the keywords, so the property mixes it rather
    * than inlining them.
    */
  private def resolveValueTraits(
      g: CssGrammar.Grammar,
      propsByName: Map[String, WebrefCss.Property],
      byType: Map[String, ValueTrait],
  ): Set[String] =
    val out                                                   = scala.collection.mutable.LinkedHashSet.empty[String]
    def typeRefsOf(grammar: CssGrammar.Grammar): List[String] =
      collectKeywordsAndTypeRefs(grammar).collect { case CssGrammar.TypeRef(name) =>
        name.takeWhile(c => c != ' ' && c != '[').trim
      }
    // Direct type-refs.
    typeRefsOf(g).foreach(t => byType.get(t).foreach(vt => out += vt.traitName))
    // One level through property-refs (shorthand grammars like `<'border-top-width'>{1,4}`).
    def walkPropRefs(grammar: CssGrammar.Grammar): Unit = grammar match
      case CssGrammar.OneOf(alts)             => alts.foreach(walkPropRefs)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.foreach(walkPropRefs)
      case CssGrammar.AllInAnyOrder(alts)     => alts.foreach(walkPropRefs)
      case CssGrammar.Sequence(items)         => items.foreach(walkPropRefs)
      case CssGrammar.Group(inner)            => walkPropRefs(inner)
      case CssGrammar.Multiplied(operand, _)  => walkPropRefs(operand)
      case CssGrammar.PropertyRef(name)       =>
        propsByName.get(name).flatMap(_.value).foreach { gStr =>
          CssGrammar.parse(gStr) match
            case Right(refAst) => typeRefsOf(refAst).foreach(t => byType.get(t).foreach(vt => out += vt.traitName))
            case Left(_)       => ()
        }
      case _ => ()
    walkPropRefs(g)
    out.toSet
  end resolveValueTraits

  /** Synthesize a property object's scaladoc from its webref metadata. Properties carry no `prose` (verified across the
    * snapshot) — only their value children do — so the object doc is the dom name, the grammar, and the
    * initial/inherited facts. Keyword `def`s get their own prose-sourced scaladoc (see [[KeywordMethod.doc]]).
    */
  private def synthDoc(p: WebrefCss.Property): Option[String] =
    p.value match
      case scala.None    => scala.None
      case Some(grammar) =>
        val initial   = p.initial.map(i => s" Initial: `$i`.").getOrElse("")
        val inherited = p.inherited.map(h => s" Inherited: $h.").getOrElse("")
        Some(s"`${p.name}` — `$grammar`.$initial$inherited")
  end synthDoc

  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString
end CssGenerator
