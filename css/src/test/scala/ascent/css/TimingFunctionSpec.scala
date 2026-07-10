package ascent.css

import zio.test.*

/** The [[TimingFunction]] value type — keyword curves + `cubic-bezier(...)` / `steps(...)`. Fixes the formerly-bare
  * `animation-timing-function` / `transition-timing-function`, which now mix `TimingFunctionish`.
  */
object TimingFunctionSpec extends ZIOSpecDefault:

  def spec = suite("TimingFunction")(
    test("keyword curves render verbatim") {
      assertTrue(
        TimingFunction.ease.render == "ease",
        TimingFunction.easeInOut.render == "ease-in-out",
        TimingFunction.stepStart.render == "step-start",
      )
    },
    test("cubic-bezier renders its four control points") {
      assertTrue(TimingFunction.cubicBezier(0.25, 0.1, 0.25, 1).render == "cubic-bezier(0.25, 0.1, 0.25, 1.0)")
    },
    test("steps(n) and steps(n, jump)") {
      assertTrue(
        TimingFunction.steps(4).render == "steps(4)",
        TimingFunction.steps(4, "jump-start").render == "steps(4, jump-start)",
      )
    },
    test("attaches to a timing property via the Timing mixin (the bare-property fix)") {
      import Styles.*
      assertTrue(
        animationTimingFunction.easeInOut.render == "animation-timing-function: ease-in-out;",
        transitionTimingFunction.cubicBezier(0.4, 0, 0.2, 1).render
          == "transition-timing-function: cubic-bezier(0.4, 0.0, 0.2, 1.0);",
      )
    },
  )
end TimingFunctionSpec
