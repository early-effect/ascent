package ascent.domgen.css

/** A generated keyword VALUE enum: a composable `CssValue` whose cases render bare tokens (`both`, `alternate`,
  * `infinite`), so a typed shorthand builder can splice them into a multi-token value like `animation: <name> <dur>
  * <timing> <count> <fill>`.
  *
  * This is the value half of a shorthand-component keyword `<type>`. Its property-bound sibling — the nested
  * `trait <Name>` in [[ValueTraitCatalog]] — still provides `animationFillMode.both` (a `Declaration`). The two coexist
  * exactly as the hand-authored `enum LineStyle` (value) and nested `trait LineStyle` (property keyword bundle) do: the
  * enum is TOP-LEVEL in `package ascent.css`, the trait is a member of `StylesValueTraits`, so their names don't clash.
  */
final case class KeywordValueEnum(
    enumName: String,        // top-level Scala enum name, e.g. "SingleAnimationFillMode"
    typeName: String,        // bare webref type name, e.g. "single-animation-fill-mode"
    grammar: Option[String], // the `<type>`'s value grammar, for the enum's scaladoc
    cases: List[KeywordMethod],
    hasNumber: Boolean, // grammar has a <number>/<integer> branch → add a `Count(Double)` case
)

/** Derives the keyword-value-enum catalog: one `enum … extends CssValue` per keyword `<type>` that appears as a
  * COMPONENT of some shorthand's any-order (`||`) / all-required (`&&`) grammar. That "shorthand component" signal is
  * what distinguishes a type that needs a composable VALUE (spliced into a shorthand) from a standalone single-keyword
  * property value (`display`, `cursor`) that only ever needs its property-bound keyword trait.
  *
  * Reuses [[ValueTraitCatalog]] for the keyword harvest, so an enum's cases are exactly its nested trait's members —
  * the two never drift.
  */
object KeywordValueCatalog:

  /** Bare type names that already have a HAND-AUTHORED `CssValue` of the same top-level name — skip generating an enum
    * for these (it would redefine the hand-written type). `<line-style>` → `enum LineStyle` in `Border.scala`.
    */
  private val handAuthored: Set[String] = Set("line-style")

  /** Build the enum catalog from the spec-wide value lookup + property list. `excluded` mirrors
    * [[CssGenerator.excludedFromValueTraits]] so the enum set aligns with the keyword-trait set.
    */
  def build(
      valuesByName: Map[String, WebrefCss.ValueDef],
      properties: List[WebrefCss.Property],
      excluded: Set[String],
  ): List[KeywordValueEnum] =
    val traits         = ValueTraitCatalog.build(valuesByName, excluded)
    val traitByType    = traits.map(t => t.typeName -> t).toMap
    val keywordTypes   = traitByType.keySet
    val componentTypes = shorthandComponentTypes(valuesByName, properties, keywordTypes)
    // Deterministic order (matches the value-trait catalog's source order for a stable diff).
    traits.iterator
      .filter(t => componentTypes.contains(t.typeName) && !handAuthored.contains(t.typeName))
      .map { t =>
        KeywordValueEnum(
          enumName = ValueTraitCatalog.traitNameFor(t.typeName), // same name the nested trait uses (top-level here)
          typeName = t.typeName,
          grammar = t.grammar,
          cases = t.keywords,
          hasNumber = grammarHasNumber(valuesByName.get(s"<${t.typeName}>")),
        )
      }
      .toList
  end build

  /** The set of keyword `<type>` bare-names referenced as a DIRECT any-order (`||`) / all-required (`&&`) component of
    * some value-def or property grammar.
    */
  private def shorthandComponentTypes(
      valuesByName: Map[String, WebrefCss.ValueDef],
      properties: List[WebrefCss.Property],
      keywordTypes: Set[String],
  ): Set[String] =
    val found                                           = scala.collection.mutable.Set.empty[String]
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
    def scan(gStr: String): Unit =
      CssGrammar.parse(gStr).foreach(ast => components(ast).filter(keywordTypes.contains).foreach(found += _))
    valuesByName.values.foreach(_.value.foreach(scan))
    properties.foreach(_.value.foreach(scan))
    found.toSet
  end shorthandComponentTypes

  /** Does this type's grammar have a `<number>` / `<integer>` branch (→ a numeric enum case alongside the keywords)? */
  private def grammarHasNumber(v: Option[WebrefCss.ValueDef]): Boolean =
    v.flatMap(_.value).exists { gStr =>
      CssGrammar.parse(gStr).toOption.exists { ast =>
        def walk(g: CssGrammar.Grammar): Boolean = g match
          case CssGrammar.TypeRef(n) =>
            val bare = n.takeWhile(c => c != ' ' && c != '[').trim
            bare == "number" || bare == "integer"
          case CssGrammar.OneOf(a)             => a.exists(walk)
          case CssGrammar.AnyOrderOneOrMore(a) => a.exists(walk)
          case CssGrammar.AllInAnyOrder(a)     => a.exists(walk)
          case CssGrammar.Sequence(a)          => a.exists(walk)
          case CssGrammar.Group(inner)         => walk(inner)
          case CssGrammar.Multiplied(op, _)    => walk(op)
          case _                               => false
        walk(ast)
      }
    }
end KeywordValueCatalog
