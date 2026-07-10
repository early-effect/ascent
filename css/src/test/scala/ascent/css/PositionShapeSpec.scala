package ascent.css

import zio.test.*

/** The [[Position]] (`background-position`) and [[BasicShape]] (`clip-path`) value types + their property mixins; the
  * mixin tests also guard the keyword-collision fix where `backgroundPosition` declares `center` via both `Positionish`
  * and its own inline grammar keyword — the generator must not emit a duplicate.
  */
object PositionShapeSpec extends ZIOSpecDefault:

  def spec = suite("Position + BasicShape")(
    suite("Position")(
      test("keyword corners render their phrase") {
        assertTrue(
          Position.center.render == "center",
          Position.topLeft.render == "top left",
          Position.bottomRight.render == "bottom right",
        )
      },
      test("at(x, y) renders an explicit length/percentage pair") {
        assertTrue(Position.at(Length.pct(20), Length.zero).render == "20.0% 0")
      },
      test("attaches to background-position via Positionish (keyword + at)") {
        import Styles.*
        assertTrue(
          backgroundPosition.center.render == "background-position: center;",
          backgroundPosition.at(Length.pct(50), Length.pct(100)).render == "background-position: 50.0% 100.0%;",
        )
      },
    ),
    suite("BasicShape")(
      test("circle with radius + position") {
        assertTrue(
          BasicShape.circle.render == "circle()",
          BasicShape.circle("closest-side", Position.center).render == "circle(closest-side at center)",
        )
      },
      test("inset from edge lengths") {
        assertTrue(BasicShape.inset(Length.px(10), Length.px(20)).render == "inset(10.0px 20.0px)")
      },
      test("polygon vertices comma-join") {
        assertTrue(BasicShape.polygon("50% 0%", "100% 100%", "0% 100%").render == "polygon(50% 0%, 100% 100%, 0% 100%)")
      },
      test("attaches to clip-path via BasicShapeish") {
        import Styles.*
        assertTrue(
          clipPath(BasicShape.circle).render == "clip-path: circle();",
          clipPath(BasicShape.inset(Length.zero)).render == "clip-path: inset(0);",
        )
      },
    ),
  )
end PositionShapeSpec
