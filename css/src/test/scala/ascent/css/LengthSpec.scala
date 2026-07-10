package ascent.css

import zio.test.*

/** The [[Length]] value type: a reusable CSS `<length-percentage>` VALUE (distinct from the `StylesFoundation.Length`
  * trait that powers `fontSize.px(24)`) that composes into [[Shadow]] offsets, [[ColorStop]]s, filter radii, and the
  * like where a `Double => Declaration` trait method can't reach.
  */
object LengthSpec extends ZIOSpecDefault:

  def spec = suite("Length")(
    test("px renders the unit suffix with platform-stable doubles") {
      assertTrue(Length.px(8).render == "8.0px")
    },
    test("rem / em / vh / vw and the rarer units render their suffixes") {
      assertTrue(
        Length.rem(1.5).render == "1.5rem",
        Length.em(2).render == "2.0em",
        Length.vh(50).render == "50.0vh",
        Length.vw(100).render == "100.0vw",
        Length.q(3).render == "3.0Q",
      )
    },
    test("zero is the unitless 0 (the one length that needs no unit)") {
      assertTrue(Length.zero.render == "0")
    },
    test("pct covers the percentage half of <length-percentage>") {
      assertTrue(Length.pct(50).render == "50.0%")
    },
    test("toString == render so it interpolates into legacy strings during migration") {
      assertTrue(Length.px(3).toString == "3.0px")
    },
    test("a Length attaches to a property via the universal CssValue overload") {
      assertTrue(Styles.width(Length.px(580)).render == "width: 580.0px;")
    },
    suite("numeric extension methods (the ergonomic surface: 14.px, 1.5.rem, 50.pct)")(
      test("Int and Double both get the unit extensions") {
        assertTrue(
          14.px.render == "14.0px",
          (1.5).rem.render == "1.5rem",
          50.pct.render == "50.0%",
          24.px.render == "24.0px",
          100.vh.render == "100.0vh",
        )
      },
      test("the bare literal 0 converts to the unitless Length.zero (a non-zero bare Int is a compile error)") {
        val z: Length = 0
        assertTrue(z.render == "0")
      },
      test("numeric extensions attach to a property too") {
        assertTrue(Styles.width(24.px).render == "width: 24.0px;")
      },
    ),
  )
end LengthSpec
