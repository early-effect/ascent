package ascent.css

/** A typed `@supports` query condition. The CSS @supports grammar's `<supports-condition>` is one of:
  *
  *   - a parenthesised property/value declaration: `(display: grid)`;
  *   - a `selector(<sel>)` test: `selector(:has(> *))`;
  *   - a `font-tech(...)` / `font-format(...)` test;
  *   - a logical composition: `<c> and <c>`, `<c> or <c>`, `not <c>`.
  *
  * Webref's `atrules[@supports].descriptors[]` is empty — there's nothing to generate. The typed surface is the
  * [[Supports]] object's hand-written constructors PLUS the killer feature: [[Supports.declaration]] takes a
  * [[Declaration]] from the generated [[Styles]] catalog, so `Supports.declaration(Styles.display.flex)` rides the same
  * type-safe path as actually writing the property in a rule body. End-to-end type safety from property to
  * @supports
  *   query.
  *
  * Composition mirrors [[MediaCondition]] / [[ContainerCondition]] (`and` / `or` / `not`) but the type is distinct, so
  * cross-mixing across at-rule kinds is a compile error.
  *
  * **Disjunction note.** CSS @supports uses the `or` keyword between disjuncts, NOT a comma — that's a real difference
  * from @media (where `,` is the disjunction). Pin both.
  */
sealed trait SupportsCondition:
  def render: String
  infix def and(other: SupportsCondition): SupportsCondition =
    SupportsCondition.Combined(this, " and ", other)
  infix def or(other: SupportsCondition): SupportsCondition =
    SupportsCondition.Combined(this, " or ", other)
  def not: SupportsCondition = SupportsCondition.Not(this)
end SupportsCondition

object SupportsCondition:
  /** `(prop: value)` — a property/value test. */
  final case class Decl(prop: String, value: String) extends SupportsCondition:
    def render: String = s"($prop: $value)"

  /** `selector(<inner>)` — a CSS Selectors level 4 selector test. */
  final case class SelectorTest(inner: String) extends SupportsCondition:
    def render: String = s"selector($inner)"

  final case class Combined(left: SupportsCondition, sep: String, right: SupportsCondition) extends SupportsCondition:
    def render: String = left.render + sep + right.render

  final case class Not(inner: SupportsCondition) extends SupportsCondition:
    def render: String = "not " + inner.render

  /** A condition produced from a hand-rolled spec snippet — the escape hatch for grammar shapes the typed surface
    * doesn't model yet (e.g. `font-tech(...)` tests).
    */
  final case class Raw(query: String) extends SupportsCondition:
    def render: String = query
end SupportsCondition

/** Typed `@supports` query catalog. The constructors here build [[SupportsCondition]] values that flow into
  * [[SupportsQuery]] just like [[MediaCondition]] flows into [[MediaQuery]].
  *
  * Hand-written (not generated) — `@supports` has no per-feature descriptor list in webref. What IS generated, and used
  * here, is the [[Styles]] catalog: `Supports.declaration` over a `Styles.foo.bar` typed Declaration produces the @supports
  * query for that exact value.
  *
  * Example:
  * {{{
  *   import ascent.css.{Supports, SupportsQuery, Styles}
  *
  *   SupportsQuery(Supports.declaration(Styles.display.grid), ...members)
  *   SupportsQuery(Supports.selector(":has(> *)"), ...members)
  *   SupportsQuery(
  *     Supports.declaration(Styles.display.grid) and Supports.selector(":has(*)"),
  *     ...members,
  *   )
  * }}}
  */
object Supports:

  /** `(prop: value)` from a string pair — for cases where you don't have a typed builder (vendor-prefixed properties,
    * custom properties, future-spec properties).
    */
  def declaration(prop: String, value: String): SupportsCondition =
    SupportsCondition.Decl(prop, value)

  /** `(prop: value)` from a typed [[Declaration]] produced by [[Styles]] — the type-safe path. The Declaration's `name`
    * becomes the property and `value` becomes the value; the same Declaration could ALSO be placed in a rule body to
    * actually apply the style, so a misspelled property name fails to compile in BOTH places.
    */
  def declaration(d: Declaration): SupportsCondition =
    SupportsCondition.Decl(d.name, d.value)

  /** `selector(<inner>)` — @supports test for whether a selector is supported. */
  def selector(inner: String): SupportsCondition =
    SupportsCondition.SelectorTest(inner)

  /** Raw escape hatch — for queries the typed surface doesn't model yet (`font-tech(...)`, `font-format(...)`, future @supports
    * extensions).
    */
  def raw(query: String): SupportsCondition = SupportsCondition.Raw(query)
end Supports
