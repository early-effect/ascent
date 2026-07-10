package ascent.css

import zio.test.*

/** The [[Clip]] value type — the legacy `clip` property's `rect(<top>, <right>, <bottom>, <left>)` shape with typed
  * lengths (`Length.auto` covers the per-edge `auto`).
  */
object ClipSpec extends ZIOSpecDefault:

  def spec = suite("Clip")(
    test("rect renders the four edges comma-joined") {
      assertTrue(
        Clip.rect(Length.zero, Length.zero, Length.zero, Length.zero).render == "rect(0, 0, 0, 0)",
        Clip.rect(2.px, 4.px, 6.px, 8.px).render == "rect(2.0px, 4.0px, 6.0px, 8.0px)",
        Clip.rect(Length.auto, 10.px, Length.auto, 10.px).render == "rect(auto, 10.0px, auto, 10.0px)",
      )
    },
    test("attaches to the clip property (Clipish) via apply + the rect shortcut") {
      import Styles.*
      assertTrue(
        clip(Clip.rect(Length.zero, Length.zero, Length.zero, Length.zero)).render == "clip: rect(0, 0, 0, 0);",
        clip.rect(Length.zero, Length.zero, Length.zero, Length.zero).render == "clip: rect(0, 0, 0, 0);",
        clip.auto.render == "clip: auto;",
      )
    },
  )
end ClipSpec
