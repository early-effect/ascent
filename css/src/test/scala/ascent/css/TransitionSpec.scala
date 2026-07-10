package ascent.css

import zio.test.*

/** The [[Transition]] value type — a typed `transition` (property + [[Time]] duration + [[TimingFunction]] + optional
  * delay), with a comma-joined multi-property form ([[Transition.list]]).
  *
  * The animated *property* is named from the TYPED property catalog (`Styles.color` → `color.property`), so a typo
  * can't silently break a transition; the duration is a typed [[Time]] and the timing a typed [[TimingFunction]]. The
  * `String` property form remains as an escape hatch for `all` / custom properties.
  */
object TransitionSpec extends ZIOSpecDefault:

  def spec = suite("Transition")(
    test("typed property + duration + default timing (ease)") {
      assertTrue(Transition(Styles.color, Time.s(0.2)).render == "color 0.2s ease")
    },
    test("typed property + typed timing function") {
      assertTrue(Transition(Styles.transform, Time.s(0.3), TimingFunction.easeOut).render == "transform 0.3s ease-out")
    },
    test("a delay renders after the timing function") {
      assertTrue(
        Transition(Styles.opacity, Time.s(0.2), TimingFunction.ease, Time.ms(50)).render == "opacity 0.2s ease 50.0ms"
      )
    },
    test("the String property form is the escape hatch (e.g. `all`)") {
      assertTrue(Transition("all", Time.s(0.2)).render == "all 0.2s ease")
    },
    test("Transition.list comma-joins multiple property transitions (the destroy-button set)") {
      val t = Transition.list(
        Transition(Styles.opacity, Time.s(0.2)),
        Transition(Styles.color, Time.s(0.2)),
        Transition(Styles.transform, Time.s(0.2)),
        Transition(Styles.background, Time.s(0.2)),
      )
      assertTrue(
        t.render == "opacity 0.2s ease, color 0.2s ease, transform 0.2s ease, background 0.2s ease"
      )
    },
    test("a Transition attaches to the transition property via the universal overload") {
      assertTrue(Styles.transition(Transition(Styles.color, Time.s(0.2))).render == "transition: color 0.2s ease;")
    },
  )
end TransitionSpec
