package ascent.css

import zio.*
import zio.test.*

/** Typed `@media` query DSL, generated from webref's `@media` descriptors, plus a raw-`String` escape hatch for grammar
  * shapes the analyzer hasn't typed yet.
  */
object MediaQueriesSpec extends ZIOSpecDefault:

  def spec = suite("Media (typed @media query DSL)")(
    suite("keyword features")(
      test("orientation has portrait/landscape") {
        val q = Media.orientation.portrait
        assertTrue(q.render == "(orientation: portrait)")
      },
      test("hover has none/hover") {
        assertTrue(
          Media.hover.none.render == "(hover: none)",
          Media.hover.hover.render == "(hover: hover)",
        )
      },
      test("prefers-reduced-motion has no-preference/reduce") {
        assertTrue(
          Media.prefersReducedMotion.reduce.render == "(prefers-reduced-motion: reduce)",
          Media.prefersReducedMotion.noPreference.render == "(prefers-reduced-motion: no-preference)",
        )
      },
      test("prefers-color-scheme has light/dark") {
        assertTrue(
          Media.prefersColorScheme.light.render == "(prefers-color-scheme: light)",
          Media.prefersColorScheme.dark.render == "(prefers-color-scheme: dark)",
        )
      },
      test("display-mode covers all five values from the @media descriptor grammar") {
        assertTrue(
          Media.displayMode.fullscreen.render == "(display-mode: fullscreen)",
          Media.displayMode.standalone.render == "(display-mode: standalone)",
          Media.displayMode.minimalUi.render == "(display-mode: minimal-ui)",
          Media.displayMode.browser.render == "(display-mode: browser)",
          Media.displayMode.pictureInPicture.render == "(display-mode: picture-in-picture)",
        )
      },
      test("pointer covers none/coarse/fine") {
        assertTrue(
          Media.pointer.none.render == "(pointer: none)",
          Media.pointer.coarse.render == "(pointer: coarse)",
          Media.pointer.fine.render == "(pointer: fine)",
        )
      },
    ),
    suite("typed-value features")(
      test("max-width / min-width accept lengths via the same .px/.em/.rem builders as Styles") {
        assertTrue(
          Media.maxWidth.px(600).render == "(max-width: 600.0px)",
          Media.minWidth.px(320).render == "(min-width: 320.0px)",
          Media.maxWidth.em(40).render == "(max-width: 40.0em)",
        )
      },
      test("aspect-ratio accepts a typed ratio and renders the canonical `a/b` form") {
        assertTrue(
          Media.aspectRatio(16, 9).render == "(aspect-ratio: 16/9)",
          Media.aspectRatio(1, 1).render == "(aspect-ratio: 1/1)",
        )
      },
      test("monochrome accepts an integer") {
        assertTrue(Media.monochrome(2).render == "(monochrome: 2)")
      },
      test("resolution accepts dpi/dppx + the keyword `infinite`") {
        assertTrue(
          Media.resolution.dpi(192).render == "(resolution: 192.0dpi)",
          Media.resolution.dppx(2).render == "(resolution: 2.0dppx)",
          Media.resolution.infinite.render == "(resolution: infinite)",
        )
      },
    ),
    suite("composition (and / not / or)")(
      test("`and` joins two conditions with ` and `") {
        val q = Media.maxWidth.px(600) and Media.prefersReducedMotion.reduce
        assertTrue(q.render == "(max-width: 600.0px) and (prefers-reduced-motion: reduce)")
      },
      test("`or` joins two conditions with `, ` (CSS @media disjunction is comma-separated)") {
        val q = Media.orientation.portrait or Media.maxWidth.px(600)
        assertTrue(q.render == "(orientation: portrait), (max-width: 600.0px)")
      },
      test("`not` prefixes a condition with `not `") {
        val q = Media.hover.none.not
        assertTrue(q.render == "not (hover: none)")
      },
      test("composition associates left-to-right cleanly: a and b and c") {
        val q = Media.minWidth.px(320) and Media.maxWidth.px(800) and Media.orientation.landscape
        assertTrue(
          q.render ==
            "(min-width: 320.0px) and (max-width: 800.0px) and (orientation: landscape)"
        )
      },
    ),
    suite("MediaQuery integration")(
      test("MediaQuery accepts a typed condition and uses its rendered string as the query") {
        val rule = Selector(
          ".banner",
          MediaQuery(
            Media.prefersReducedMotion.reduce,
            Selector("::after", Declaration("transition", "none")),
          ),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains("@media (prefers-reduced-motion: reduce) {"),
          rendered.contains(".banner::after {"),
          rendered.contains("transition: none;"),
        )
      },
      test("MediaQuery accepts a raw String for forward-compat (paste raw spec snippets)") {
        val rule = Selector(
          ".x",
          MediaQuery("(min-resolution: 2x) and (color)", Declaration("color", "red")),
        )
        val rendered = rule.render
        assertTrue(rendered.contains("@media (min-resolution: 2x) and (color) {"))
      },
    ),
  )
end MediaQueriesSpec
