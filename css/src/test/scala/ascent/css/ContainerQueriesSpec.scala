package ascent.css

import zio.*
import zio.test.*

/** Typed `@container` query DSL: descriptors are generated into [[ContainerFeatures]] and surfaced via [[Container]].
  */
object ContainerQueriesSpec extends ZIOSpecDefault:

  def spec = suite("Container (typed @container query DSL)")(
    suite("typed-value features")(
      test("width / minWidth / maxWidth as Length") {
        assertTrue(
          Container.width.px(400).render == "(width: 400.0px)",
          Container.minWidth.px(320).render == "(min-width: 320.0px)",
          Container.maxWidth.em(40).render == "(max-width: 40.0em)",
        )
      },
      test("inline-size / block-size are container-specific length features") {
        assertTrue(
          Container.inlineSize.px(600).render == "(inline-size: 600.0px)",
          Container.blockSize.px(400).render == "(block-size: 400.0px)",
          Container.minInlineSize.px(320).render == "(min-inline-size: 320.0px)",
          Container.maxBlockSize.px(800).render == "(max-block-size: 800.0px)",
        )
      },
      test("aspect-ratio accepts a typed ratio") {
        assertTrue(Container.aspectRatio(16, 9).render == "(aspect-ratio: 16/9)")
      },
    ),
    suite("keyword features")(
      test("orientation has portrait/landscape (shared with @media)") {
        assertTrue(
          Container.orientation.portrait.render == "(orientation: portrait)",
          Container.orientation.landscape.render == "(orientation: landscape)",
        )
      }
    ),
    suite("composition")(
      test("`and`/`or`/`not` on ContainerCondition produce a ContainerCondition") {
        val q = Container.minWidth.px(400) and Container.orientation.portrait
        assertTrue(q.render == "(min-width: 400.0px) and (orientation: portrait)")
      }
    ),
    suite("ContainerQuery wrapper")(
      test("ContainerQuery wraps a typed condition into `@container (q) { rules }`") {
        val rule = Selector(
          ".card",
          ContainerQuery(
            Container.minWidth.px(400),
            Selector(":hover", Declaration("background", "blue")),
          ),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains("@container (min-width: 400.0px) {"),
          rendered.contains(".card:hover {"),
          rendered.contains("background: blue;"),
        )
      },
      test("ContainerQuery supports an optional container-name: `@container my-card (q) { ... }`") {
        val rule = Selector(
          ".card",
          ContainerQuery.named("my-card", Container.minWidth.px(400), Declaration("padding", "1rem")),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains("@container my-card (min-width: 400.0px) {"),
          rendered.contains(".card {"),
          rendered.contains("padding: 1rem;"),
        )
      },
      test("ContainerQuery accepts a raw String for forward-compat (style queries, parens-grammar)") {
        val rule = Selector(
          ".x",
          ContainerQuery("style(--theme: dark)", Declaration("color", "white")),
        )
        val rendered = rule.render
        assertTrue(rendered.contains("@container style(--theme: dark) {"))
      },
    ),
  )
end ContainerQueriesSpec
