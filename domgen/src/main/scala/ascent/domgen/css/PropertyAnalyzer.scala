package ascent.domgen.css

/** A bare-keyword method to emit on a property object: `def <scalaName>: Declaration = apply("<domName>")`.
  *
  * Two names because CSS keywords are hyphenated and Scala identifiers aren't: `space-between` becomes
  * `def spaceBetween: Declaration = apply("space-between")`. The optional `doc` carries the keyword's webref prose,
  * rendered as scaladoc on the emitted `def` so autocomplete shows what each keyword means.
  */
final case class KeywordMethod(scalaName: String, domName: String, doc: Option[String] = None)

/** The shape of a CSS property as the codegen will emit it: which value-grammar traits to mix in (Length / Percent /
  * etc.) and which extra keyword methods to define.
  *
  * Anything beyond what's captured here (function notation, multi-token shorthand grammars, complex multipliers) falls
  * back to the universal `DS.apply(String)` escape hatch in the generated object.
  */
final case class PropertyShape(traits: List[String], keywords: List[KeywordMethod])

/** Heuristic analyzer: turns a parsed CSS [[CssGrammar.Grammar]] into a [[PropertyShape]] by inspecting only the
  * **top-level alternatives** of the grammar.
  *
  * **Top-level alternative** means: the grammar itself, or — if the grammar is an `OneOf` — each of its branches.
  * Anything nested deeper (groups, multipliers, sequences, function calls) is treated as opaque and contributes
  * nothing.
  *
  * This is intentionally conservative: it produces a clean DSL for the ~80% of CSS properties whose value grammars are
  * simple alternations of named keywords + standard type-refs, and lets everything else flow through the universal
  * `apply(String)` escape hatch on `DS`.
  */
object PropertyAnalyzer:

  /** Map type-ref names to the traits in [[ascent.css.Styles]] they imply.
    *
    * Covers the canonical CSS value types plus a small set of widely-used semantic aliases webref uses that resolve to
    * the same shapes (`<opacity-value>` → `<number>`, `<line-width>` → length-or-keyword, etc.). A full `<typedef>`
    * chain resolver is beyond v1's scope; the analyzer's [[CssGenerator.resolveTypeRefTraits]] walk handles type-refs
    * that ARE defined globally.
    */
  private val typeRefTraits: Map[String, List[String]] = Map(
    "length"            -> List("Length"),
    "length-percentage" -> List("Length", "Percent"),
    "percentage"        -> List("Percent"),
    "color"             -> List("ColorLike"),
    "number"            -> List("Numeric"),
    "integer"           -> List("Numeric"),
    // Semantic aliases used widely in webref grammars.
    "opacity-value" -> List("Numeric"),
    "alpha-value"   -> List("Numeric"),
    "ratio"         -> List("Numeric"),
    "flex"          -> List("Numeric"), // <flex> is a fractional unit, but Numeric is the closest fit
    "border-width"  -> List("Length"),
    "size"          -> List("Length"),
    // Value-ADT mixins — wire a property to its hand-written typed-value builder (see StylesFoundation `*ish`
    // traits). These types are thus EXCLUDED from the generated keyword-trait catalog (knownTypeRefNames).
    "transform-list"      -> List("Transformish"),
    "transform-function"  -> List("Transformish"),
    "filter-value-list"   -> List("Filterish"),
    "filter-function"     -> List("Filterish"),
    "shadow"              -> List("Shadowish"),
    "spread-shadow"       -> List("Shadowish"),
    "image"               -> List("Imageish"),
    "bg-image"            -> List("Imageish"),
    "angle"               -> List("Angleish"),
    "time"                -> List("Timeish"),
    "single-transition"   -> List("Transitionish"),
    "easing-function"     -> List("TimingFunctionish"),
    "font-family"         -> List("FontFamilyish"),
    "family-name"         -> List("FontFamilyish"),
    "font-family-name"    -> List("FontFamilyish"),
    "generic-family"      -> List("FontFamilyish"),
    "generic-font-family" -> List("FontFamilyish"),
    "position"            -> List("Positionish"),
    "bg-position"         -> List("Positionish"),
    "basic-shape"         -> List("BasicShapeish"),
    "track-list"          -> List("GridTrackish"),
    "auto-track-list"     -> List("GridTrackish"),
    "explicit-track-list" -> List("GridTrackish"),
    "track-size"          -> List("GridTrackish"),
    "rect()"              -> List("Clipish"), // the legacy `clip` property's `rect(<top>,<right>,<bottom>,<left>)`
  )

  /** Bare type names already mapped to a hand-written foundation/ADT trait — the value-trait catalog excludes these so
    * a `<type>` is never claimed by both a curated trait AND a generated keyword trait.
    *
    * `<line-width>` is intentionally NOT here: it's a keyword-bearing type (`thin/medium/thick/hairline`) that becomes
    * the generated `trait LineWidth`. (Its `<length>` branch is covered because referencing properties also mix
    * `Length` directly from their own grammar.)
    */
  def knownTypeRefNames: Set[String] = typeRefTraits.keySet

  /** CSS keywords that are represented as TRAITS rather than methods (so that all properties sharing the keyword get a
    * uniform `def auto: Declaration` from the trait, not a per-property duplicate).
    */
  private val keywordTraits: Map[String, String] = Map(
    "auto"   -> "Auto",
    "none"   -> "None",
    "normal" -> "Normal",
  )

  /** Method names that the foundation traits already define (Length, Percent, ColorLike, Numeric, etc.). Surfacing a
    * CSS keyword like `px` as `def px: Declaration` on a property that also extends `Length` would be an illegal
    * redefinition (the trait's `val px: Double => Declaration` is the existing member). Filter these out to keep the
    * foundation API authoritative.
    *
    * The set covers every member name in `StylesFoundation.{Length, Percent, ColorLike, Numeric}`. `Auto.auto`,
    * `None.none`, `Normal.normal` are already handled via `keywordTraits` (those keyword names map to the trait, not a
    * method).
    */
  private[css] val foundationMemberNames: Set[String] = Set(
    // Length units
    "px",
    "em",
    "rem",
    "pt",
    "pc",
    "cm",
    "mm",
    "in",
    "q",
    "ex",
    "ch",
    "vh",
    "vw",
    "vmin",
    "vmax",
    "zero",
    // Percent
    "pct",
    // ColorLike
    "rgb",
    "rgba",
    // CSS-wide cascade keywords — defined once on `DS` (StylesFoundation), so a property must
    // never re-emit them as its own keyword method (illegal redefinition).
    "inherit",
    "initial",
    "unset",
    "revert",
    "revertLayer",
  )

  /** Canonical order in which the FOUNDATION traits appear on the generated `extends ... with ...` chain. Order is
    * stable so the generated source diffs cleanly; it matches the structure of the hand-written `Styles.scala`
    * (positional traits first, keyword traits second, then the less-common ones). Generated value-type traits (e.g.
    * `LineWidth`) and hand-written ADT mixins (`Transformish`, …) are NOT listed here — they sort alphabetically after
    * the canonical foundation traits via [[orderTraits]].
    */
  private val canonicalTraitOrder: List[String] =
    List("Length", "Percent", "Auto", "None", "Normal", "ColorLike", "Numeric")

  /** Stable trait ordering: the canonical foundation traits first (in their fixed order), then everything else
    * (generated value-type traits, ADT mixins) alphabetically, so the generated `with …` chain diffs cleanly.
    */
  private def orderTraits(traits: collection.Set[String]): List[String] =
    val canonicalSet = canonicalTraitOrder.toSet
    canonicalTraitOrder.filter(traits.contains) ++ traits.filterNot(canonicalSet.contains).toList.sorted

  /** Analyze the grammar and produce the [[PropertyShape]]. */
  def analyze(g: CssGrammar.Grammar): PropertyShape =
    analyze(g, extraKeywords = Nil)

  /** Analyze the grammar with extra keywords contributed externally (e.g. from the property's own `values:` listing in
    * the webref JSON). External keywords are appended after the grammar-derived ones; duplicates are deduped on
    * Scala-name.
    */
  def analyze(g: CssGrammar.Grammar, extraKeywords: List[String]): PropertyShape =
    analyze(g, extraKeywords, Set.empty)

  /** Full-form: analyze with extra keywords AND extra foundation traits resolved externally (e.g. by walking
    * `<opacity-value>` → `<number> | <percentage>` through the spec-wide value lookup). The resolved traits merge with
    * the grammar-derived ones.
    *
    * `traitOwnedKeywords` is the union of keyword Scala-names owned by every trait the property mixes in (foundation +
    * generated value-traits). Any inline keyword in that set is DROPPED — the mixed-in trait already provides it, so
    * re-declaring it on the property would be an illegal redefinition. This is what lets `<line-width>`'s
    * `thin/medium/thick/hairline` live once on `trait LineWidth` instead of inlined on 34 properties.
    */
  def analyze(
      g: CssGrammar.Grammar,
      extraKeywords: List[String],
      extraTraits: Set[String],
      traitOwnedKeywords: Set[String] = Set.empty,
  ): PropertyShape =
    val branches = topLevelAlternatives(g)
    val traits   = scala.collection.mutable.LinkedHashSet.empty[String]
    val keywords = scala.collection.mutable.LinkedHashMap.empty[String, KeywordMethod]
    branches.foreach {
      case CssGrammar.TypeRef(name) =>
        // Strip any inline range like `<integer [0,∞]>` -> "integer".
        val baseName = name.takeWhile(c => c != ' ' && c != '[').trim
        typeRefTraits.get(baseName).foreach(_.foreach(traits += _))
      case CssGrammar.Keyword(name) =>
        addKeyword(name, traits, keywords)
      case _ =>
        // Groups, multipliers, sequences, functions, property-refs: opaque in v1.
        ()
    }
    extraKeywords.foreach(addKeyword(_, traits, keywords))
    extraTraits.foreach(traits += _)
    PropertyShape(
      traits = orderTraits(traits),
      keywords = keywords.values.filterNot(km => traitOwnedKeywords.contains(km.scalaName)).toList,
    )
  end analyze

  private def addKeyword(
      name: String,
      traits: scala.collection.mutable.LinkedHashSet[String],
      keywords: scala.collection.mutable.LinkedHashMap[String, KeywordMethod],
  ): Unit =
    keywordTraits.get(name) match
      case Some(t)    => traits += t
      case scala.None =>
        val scalaName = camelCase(name)
        // Skip names that would shadow / illegally redefine foundation-trait members.
        // Dedup the rest by Scala name.
        if !foundationMemberNames.contains(scalaName) && !keywords.contains(scalaName) then
          keywords += (scalaName -> KeywordMethod(scalaName, name))

  /** Get the keyword/type-ref-bearing alternatives reachable through the surface layers of a grammar: `OneOf`,
    * `AnyOrderOneOrMore`, `AllInAnyOrder`, `Group`, `Multiplied`, and `Sequence`. Stops at `FunctionCall` — those
    * produce a function value, not alternatives.
    *
    * **Semantic note on `Sequence`.** Strictly, a Sequence is "items in this exact order" — its components co-occur,
    * they're not alternatives. But many real CSS grammars have the shape `<X>? <Y>` where `<X>` is optional, so `<Y>`
    * IS effectively a top-level alternative when `<X>` is absent. We surface all sequence components as candidate
    * alternatives; the worst case is a few "extra" keywords on the property that the spec allows in conjunction with
    * another value. Users get the typed shorthand they expect (`alignItems.center`); the multi-token form remains
    * available via `apply(String)`.
    */
  private def topLevelAlternatives(g: CssGrammar.Grammar): List[CssGrammar.Grammar] =
    g match
      case CssGrammar.OneOf(alts)             => alts.flatMap(topLevelAlternatives)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.flatMap(topLevelAlternatives)
      case CssGrammar.AllInAnyOrder(alts)     => alts.flatMap(topLevelAlternatives)
      case CssGrammar.Sequence(items)         => items.flatMap(topLevelAlternatives)
      case CssGrammar.Group(inner)            => topLevelAlternatives(inner)
      case CssGrammar.Multiplied(operand, _)  => topLevelAlternatives(operand)
      case other                              => List(other)

  /** Convert `space-between` → `spaceBetween` for a Scala identifier. */
  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString
end PropertyAnalyzer
