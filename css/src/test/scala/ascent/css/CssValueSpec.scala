package ascent.css

import zio.test.*

/** The [[CssValue]] base and the universal `DS.apply(CssValue)` overload that attaches any typed value to a property by
  * name, without shadowing the existing `apply(String)` / `Numeric` overloads.
  */
object CssValueSpec extends ZIOSpecDefault:

  import Styles.*

  /** A minimal hand-rolled value, standing in for the real value types (Color, Length, …) added later. */
  private final case class StubValue(s: String) extends CssValue:
    def render: String = s

  def spec = suite("CssValue")(
    test("a CssValue attaches to any property via apply(CssValue), rendering its `render` as the value") {
      assertTrue(color(StubValue("rebeccapurple")).render == "color: rebeccapurple;")
    },
    test("the same value attaches to a bare DS property (no value-grammar traits)") {
      assertTrue(background(StubValue("red")).render == "background: red;")
    },
    suite("overload resolution — the new overload must not disturb the existing ones")(
      test("a String argument still resolves to apply(String), not apply(CssValue)") {
        assertTrue(color("white").render == "color: white;")
      },
      test("an Int argument still resolves to Numeric.apply(Int)") {
        assertTrue(zIndex(5).render == "z-index: 5;")
      },
      test("a Double argument still resolves to Numeric.apply(Double)") {
        assertTrue(opacity(0.5).render == "opacity: 0.5;")
      },
    ),
  )
end CssValueSpec
