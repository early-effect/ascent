package ascent.css

import ascent.css.Styles.*
import zio.test.*

/** Generated keyword VALUE enums ([[ascent.css.generated]] `StylesKeywordValues`): each case renders a bare CSS token
  * and attaches to a property via the universal `apply(CssValue)`, so it composes into a typed shorthand. The sibling
  * property-bound keyword trait (`animationFillMode.both`) is unaffected — both forms coexist.
  */
object KeywordValuesSpec extends ZIOSpecDefault:

  def spec = suite("keyword value enums")(
    test("a keyword case renders its bare token") {
      assertTrue(
        SingleAnimationFillMode.Both.render == "both",
        SingleAnimationDirection.AlternateReverse.render == "alternate-reverse",
        SingleAnimationIterationCount.Infinite.render == "infinite",
        RepeatStyle.NoRepeat.render == "no-repeat",
        VisualBox.BorderBox.render == "border-box",
      )
    },
    test("the <number> branch carries a numeric value (platform-stable formatting)") {
      assertTrue(SingleAnimationIterationCount.Count(3).render == "3.0")
    },
    test("attaches to a property as a value via apply(CssValue)") {
      assertTrue(
        animationFillMode(SingleAnimationFillMode.Both).render == "animation-fill-mode: both;",
        backgroundRepeat(RepeatStyle.Round).render == "background-repeat: round;",
      )
    },
    test("the property-bound keyword sibling still works (coexistence)") {
      // The nested trait member and the value enum share a name but different scope — both resolve.
      assertTrue(
        animationFillMode.both.render == "animation-fill-mode: both;",
        SingleAnimationFillMode.Both.render == "both",
      )
    },
  )
end KeywordValuesSpec
