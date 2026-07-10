package ascent.css

import zio.*
import zio.test.*

/** Typed `@supports` query DSL, built from the generated `Styles` catalog rather than per-feature descriptors, so a
  * misspelled property fails to compile in both the rule body AND the supports query. Composition types are distinct
  * from [[MediaCondition]] / [[ContainerCondition]] so cross-mixing is a compile error.
  */
object SupportsQueriesSpec extends ZIOSpecDefault:

  def spec = suite("Supports (typed @supports query DSL)")(
    suite("declaration() constructor")(
      test("Supports.declaration(prop, value) renders as `(prop: value)`") {
        assertTrue(Supports.declaration("display", "grid").render == "(display: grid)")
      },
      test("Supports.declaration(Declaration) reuses the typed Styles catalog") {
        val typed = Styles.display.flex // Declaration("display", "flex")
        assertTrue(Supports.declaration(typed).render == "(display: flex)")
      },
    ),
    suite("selector() constructor")(
      test("Supports.selector(\":has(> *)\") renders as function-call `selector(:has(> *))`, NOT parens-wrapped") {
        assertTrue(
          Supports.selector(":has(> *)").render == "selector(:has(> *))"
        )
      }
    ),
    suite("composition")(
      test("`and` joins two SupportsConditions") {
        val q = Supports.declaration("display", "grid") and Supports.selector(":has(*)")
        assertTrue(q.render == "(display: grid) and selector(:has(*))")
      },
      test("`or` joins disjuncts with the `or` keyword, NOT a comma (unlike @media)") {
        val q = Supports.declaration("display", "grid") or Supports.declaration("display", "flex")
        assertTrue(q.render == "(display: grid) or (display: flex)")
      },
      test("`not` prefixes a condition") {
        assertTrue(
          Supports.declaration("display", "grid").not.render == "not (display: grid)"
        )
      },
    ),
    suite("SupportsQuery wrapper")(
      test("SupportsQuery wraps a typed condition into `@supports (q) { rules }`") {
        val rule = Selector(
          ".grid",
          SupportsQuery(
            Supports.declaration(Styles.display.flex),
            Selector(":hover", Declaration("color", "red")),
          ),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains("@supports (display: flex) {"),
          rendered.contains(".grid:hover {"),
          rendered.contains("color: red;"),
        )
      },
      test("SupportsQuery accepts a raw String for forward-compat") {
        val rule = Selector(
          ".x",
          SupportsQuery("font-tech(color-COLRv1)", Declaration("font-family", "Special")),
        )
        val rendered = rule.render
        assertTrue(rendered.contains("@supports font-tech(color-COLRv1) {"))
      },
    ),
  )
end SupportsQueriesSpec
