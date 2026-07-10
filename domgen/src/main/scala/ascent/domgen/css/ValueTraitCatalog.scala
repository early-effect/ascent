package ascent.domgen.css

/** A generated CSS value-type TRAIT: a reusable bundle of keyword methods that every property whose grammar references
  * the underlying `<type>` mixes in (`with LineWidth`), instead of each property inlining the same `def thin/medium/…`.
  *
  * Mirrors the hand-written foundation traits (`Length`, `ColorLike`): a `trait <name> extends DS` whose members are
  * `def <kw>: Declaration = apply("<kw>")`. Scala trait linearization dedupes when a property pulls the same keyword
  * from two fragments — where inlined `def`s would be an illegal redefinition.
  */
final case class ValueTrait(
    traitName: String,       // Scala trait name, e.g. "LineWidth"
    typeName: String,        // bare webref type name, e.g. "line-width"
    grammar: Option[String], // the `<type>`'s value grammar, for the trait's scaladoc
    keywords: List[KeywordMethod],
    extendsTraits: List[String], // foundation keyword-traits this trait composes (Auto/None/Normal) — never re-declared
)

/** Derives the value-type-trait catalog from the spec-wide `<type>` definitions.
  *
  * Every webref `<type>` whose transitive expansion contributes ≥1 literal keyword becomes a trait. This is the keyword
  * half of the "type-ref → trait" model (the value-ADT half — `<color>`→ColorLike, `<transform-function>`→ Transformish
  * — is hand-curated in [[PropertyAnalyzer]]). A `<type>` that maps to a hand-written ADT trait is EXCLUDED here (its
  * keywords, if any, live on the ADT), so the two halves never both claim the same type.
  */
object ValueTraitCatalog:

  /** Build the catalog from the spec-wide value lookup. `excluded` is the set of bare type names already owned by a
    * hand-written ADT/foundation trait (so we don't generate a competing keyword trait for `<color>`, `<length>`, …).
    */
  def build(
      valuesByName: Map[String, WebrefCss.ValueDef],
      excluded: Set[String],
  ): List[ValueTrait] =
    // Pass 1 — decide which bare types QUALIFY as traits: those contributing ≥1 own keyword at their own level
    // (direct value-children + own-grammar literals + keywords pulled up from nested NON-qualifying structural
    // types), excluding foundation-owned and ADT-owned types. Computed via a fixpoint because "own keywords"
    // depends on which nested types are themselves traits (a nested trait's keywords are NOT pulled up).
    val candidates = valuesByName.values.toList
      .filter(_.kind == "type")
      .map(v => stripAngles(v.name))
      .toSet
      .filterNot(excluded.contains)
    // A type qualifies if its shallow keyword harvest (not descending into OTHER candidates) yields ≥1 keyword that
    // ISN'T a foundation keyword (auto/none/normal). A type contributing only `none` is not its own trait — it just
    // composes `None`; emitting a member-less trait would leave dangling `with X` references (pass 2 drops it).
    val qualifying: Set[String] =
      candidates.filter { bare =>
        valuesByName.get(s"<$bare>").exists { v =>
          ownKeywords(v, valuesByName, excluded, candidates).exists(k => !keywordTraitFor.contains(k.domName))
        }
      }

    // Pass 2 — build each qualifying trait: own keywords as defs, nested qualifying types + foundation
    // keyword-traits composed as `extends DS with …`.
    val out = scala.collection.mutable.LinkedHashMap.empty[String, ValueTrait]
    valuesByName.values.toList
      .filter(_.kind == "type")
      .foreach { v =>
        val bare = stripAngles(v.name)
        if qualifying.contains(bare) then
          val all = ownKeywords(v, valuesByName, excluded, qualifying)
          // auto/none/normal → compose the foundation Auto/None/Normal trait, never re-declare.
          val ownKws      = all.filterNot(k => keywordTraitFor.contains(k.domName))
          val foundationT = all.flatMap(k => keywordTraitFor.get(k.domName)).distinct
          // Nested qualifying types referenced in this type's grammar → compose their generated traits.
          val nestedTraits = nestedQualifyingTypes(v, valuesByName, qualifying).toList.sorted.map(traitNameFor)
          val extendsT     = (foundationT ++ nestedTraits).distinct
          if ownKws.nonEmpty then
            out(traitNameFor(bare)) = ValueTrait(traitNameFor(bare), bare, v.value, ownKws, extendsT)
        end if
      }
    out.values.toList
  end build

  /** Keywords owned by a hand-written foundation trait — a generated value-trait composes the trait instead of
    * re-declaring the keyword. Mirrors [[PropertyAnalyzer.keywordTraits]].
    */
  private val keywordTraitFor: Map[String, String] =
    Map("auto" -> "Auto", "none" -> "None", "normal" -> "Normal")

  /** Bare names of nested `<type>`s referenced (transitively through structural, NON-qualifying types) by `root`'s
    * grammar that ARE themselves qualifying traits — these get composed via `extends`, not flattened.
    */
  private def nestedQualifyingTypes(
      root: WebrefCss.ValueDef,
      valuesByName: Map[String, WebrefCss.ValueDef],
      qualifying: Set[String],
  ): Set[String] =
    val found    = scala.collection.mutable.LinkedHashSet.empty[String]
    val rootBare = stripAngles(root.name)
    def walk(v: WebrefCss.ValueDef, depth: Int, visited: Set[String]): Unit =
      if depth < 5 then
        v.value.foreach { gStr =>
          CssGrammar.parse(gStr).foreach { ast =>
            surfaced(ast).foreach {
              case CssGrammar.TypeRef(n) =>
                val bare = stripAngles(n.takeWhile(c => c != ' ' && c != '[').trim)
                if bare != rootBare && !visited.contains(bare) then
                  if qualifying.contains(bare) then found += bare // a trait: compose, don't descend
                  else valuesByName.get(s"<$bare>").foreach(child => walk(child, depth + 1, visited + bare))
              case _ => ()
            }
          }
        }
    walk(root, 0, Set(rootBare))
    found.toSet
  end nestedQualifyingTypes

  /** The trait name a given bare type resolves to (for wiring on properties). Only types that actually produce a trait
    * (non-empty keywords, not excluded) appear; callers should consult the built catalog, but this keeps naming in one
    * place.
    */
  def traitNameFor(bareTypeName: String): String =
    val parts = bareTypeName.split('-').filter(_.nonEmpty)
    val camel = parts.map(_.capitalize).mkString
    // Avoid colliding with the hand-written foundation trait names.
    if reservedTraitNames.contains(camel) then camel + "Kw" else camel

  /** Scala names already taken in package `ascent.css` (or exported into the open `package ascent`) — a generated
    * keyword trait must not shadow them, else a property catalog `with <Name>` (or a user's `import ascent.*`) becomes
    * ambiguous. Covers the foundation/ADT traits AND the hand-written authoring + value types
    * (`CounterStyle`/`Color`/`FontFace`/…). A clashing generated trait gets a `Kw` suffix (`CounterStyle` →
    * `CounterStyleKw`).
    */
  private val reservedTraitNames: Set[String] =
    Set(
      // Foundation value-grammar traits + base.
      "DS",
      "Length",
      "Percent",
      "LengthPercent",
      "Auto",
      "None",
      "Normal",
      "Numeric",
      "ColorLike",
      // Hand-written value-type ADTs (exported via ascent.*).
      "CssValue",
      "Color",
      "ColorStop",
      "Angle",
      "Time",
      "Gradient",
      "Shadow",
      "Transform",
      "Filter",
      "Transition",
      // CSS authoring types + at-rule builders (exported via ascent.*).
      "CssClass",
      "CssScope",
      "GlobalStyle",
      "GlobalRule",
      "StyleSink",
      "StateAttr",
      "Declaration",
      "Selector",
      "CssMember",
      "AtRuleBlock",
      "MediaQuery",
      "ContainerQuery",
      "SupportsQuery",
      "MediaCondition",
      "ContainerCondition",
      "SupportsCondition",
      "Styles",
      "Media",
      "Container",
      "Supports",
      "FontFace",
      "Page",
      "CounterStyle",
      "Keyframes",
      "Frame",
      "FontFaceDescriptors",
      "PageDescriptors",
      "CounterStyleDescriptors",
      "Tooltip",
    )

  /** The literal keywords a `<type>` OWNS at its own level: its direct value-children, its own-grammar literals, and
    * keywords pulled up from nested STRUCTURAL types — but it stops at (does not descend into) any nested type in
    * `stopAt` (a foundation/ADT-excluded type or a fellow qualifying trait), because those keywords belong to that
    * type's own trait. Bounded depth + cycle guard. Foundation member names (`px`/`rgb`/cascade keywords) are skipped.
    */
  private def ownKeywords(
      root: WebrefCss.ValueDef,
      valuesByName: Map[String, WebrefCss.ValueDef],
      excluded: Set[String],
      stopAt: Set[String],
  ): List[KeywordMethod] =
    val seen     = scala.collection.mutable.LinkedHashMap.empty[String, KeywordMethod]
    val rootBare = stripAngles(root.name)

    def addKeyword(name: String, doc: Option[String]): Unit =
      val scalaName = camelCase(name)
      if isKeywordShaped(name) && !seen.contains(scalaName) && !PropertyAnalyzer.foundationMemberNames.contains(
          scalaName
        )
      then seen(scalaName) = KeywordMethod(scalaName, name, doc)

    def walkType(v: WebrefCss.ValueDef, depth: Int, visited: Set[String]): Unit =
      v.children.foreach { c =>
        if c.kind == "value" && isKeywordShaped(c.name) then addKeyword(c.name, c.prose)
      }
      if depth < 5 then
        v.value.foreach { gStr =>
          CssGrammar.parse(gStr).foreach(ast => walkGrammar(ast, depth, visited))
        }
    end walkType

    def walkGrammar(g: CssGrammar.Grammar, depth: Int, visited: Set[String]): Unit =
      surfaced(g).foreach {
        case CssGrammar.Keyword(k) => addKeyword(k, scala.None)
        case CssGrammar.TypeRef(n) =>
          val bare = stripAngles(n.takeWhile(c => c != ' ' && c != '[').trim)
          // Stop at the root itself, foundation/ADT-excluded types, and fellow trait types (their keywords are
          // theirs to own). Only descend into nested STRUCTURAL types, pulling their keywords up to this level.
          if bare != rootBare && !excluded.contains(bare) && !stopAt.contains(bare) && !visited.contains(bare) then
            valuesByName.get(s"<$bare>").foreach(child => walkType(child, depth + 1, visited + bare))
        case _ => ()
      }

    walkType(root, 0, Set(rootBare))
    seen.values.toList
  end ownKeywords

  /** Surface the keyword/type-ref nodes reachable through the branch-like grammar structures (mirrors
    * `CssGenerator.collectKeywordsAndTypeRefs`). Stops at `FunctionCall` (a function value, not a keyword set).
    */
  private def surfaced(g: CssGrammar.Grammar): List[CssGrammar.Grammar] =
    g match
      case CssGrammar.OneOf(alts)             => alts.flatMap(surfaced)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.flatMap(surfaced)
      case CssGrammar.AllInAnyOrder(alts)     => alts.flatMap(surfaced)
      case CssGrammar.Sequence(items)         => items.flatMap(surfaced)
      case CssGrammar.Group(inner)            => surfaced(inner)
      case CssGrammar.Multiplied(operand, _)  => surfaced(operand)
      case CssGrammar.Keyword(_)              => List(g)
      case CssGrammar.TypeRef(_)              => List(g)
      case _                                  => Nil

  private def isKeywordShaped(s: String): Boolean = s.matches("[a-zA-Z][a-zA-Z0-9-]*")

  private def stripAngles(s: String): String =
    s.stripPrefix("<").stripSuffix(">")

  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString
end ValueTraitCatalog
