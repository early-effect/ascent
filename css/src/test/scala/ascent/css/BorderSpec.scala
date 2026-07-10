package ascent.css

import zio.test.*

/** The [[Border]] shorthand value type — `<line-width> || <line-style> || <color>`, all parts optional — and its
  * [[LineStyle]] enum.
  */
object BorderSpec extends ZIOSpecDefault:

  def spec = suite("Border")(
    test("width + style + color renders the canonical triple") {
      assertTrue(
        Border(1.px, LineStyle.Solid, Color.hex("#ff00aa")).render == "1.0px solid rgb(255, 0, 170)"
      )
    },
    test("style-only / width+style partials") {
      assertTrue(
        Border(style = LineStyle.Dashed).render == "dashed",
        Border(width = 2.px, style = LineStyle.Solid).render == "2.0px solid",
      )
    },
    test("LineStyle keywords render their CSS spelling") {
      assertTrue(
        LineStyle.Solid.render == "solid",
        LineStyle.Dashed.render == "dashed",
        LineStyle.None.render == "none",
      )
    },
    test("attaches to the border property + the side shorthands (Borderish)") {
      import Styles.*
      assertTrue(
        border(Border(1.px, LineStyle.Solid, Color.transparent)).render == "border: 1.0px solid transparent;",
        borderBottom(Border(1.px, LineStyle.Solid, Color.hex("#9b88c4"))).render
          == "border-bottom: 1.0px solid rgb(155, 136, 196);",
      )
    },
    test("the ergonomic Border.solid helper") {
      assertTrue(Border.solid(1.px, Color.transparent).render == "1.0px solid transparent")
    },
    test("multi-length box shorthands (LengthBox): 2 + 3 + 4 typed values") {
      import Styles.*
      assertTrue(
        inset(0, 14.px).render == "inset: 0 14.0px;",
        margin(0, 14.px, 0, 20.px).render == "margin: 0 14.0px 0 20.0px;",
        borderWidth(1.px, 2.px, 3.px).render == "border-width: 1.0px 2.0px 3.0px;",
        borderRadius(4.px, 8.px, 4.px, 8.px).render == "border-radius: 4.0px 8.0px 4.0px 8.0px;",
      )
    },
  )
end BorderSpec
