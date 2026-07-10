package ascent.css

import zio.test.*

/** The [[Time]] value type — a CSS `<time>` (`s`/`ms`) for transition/animation durations and delays. A prerequisite
  * for [[Transition]].
  */
object TimeSpec extends ZIOSpecDefault:

  def spec = suite("Time")(
    test("seconds and milliseconds render their suffixes with platform-stable doubles") {
      assertTrue(
        Time.s(0.2).render == "0.2s",
        Time.ms(150).render == "150.0ms",
      )
    },
    test("toString == render for interpolation during migration") {
      assertTrue(Time.s(0.35).toString == "0.35s")
    },
    test("a Time attaches to a property via the universal overload") {
      assertTrue(Styles.transitionDuration(Time.s(0.2)).render == "transition-duration: 0.2s;")
    },
  )
end TimeSpec
