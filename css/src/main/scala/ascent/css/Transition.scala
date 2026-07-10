package ascent.css

/** A composable CSS `transition` — one animated property with its [[Time]] duration, [[TimingFunction]], and optional
  * delay.
  *
  * The animated `property` is a CSS property NAME (transitions reference property *names*, not values). Prefer the
  * typed constructors that take a property catalog object — `Transition(Styles.color, Time.s(0.2))` reads the name from
  * `color.property`, so a typo or renamed property is a compile error, not a silent dead transition. The `String`
  * constructors remain for the open-ended cases (custom properties, `all`). Timing defaults to `ease`. Use
  * [[Transition.list]] for the comma-joined multi-property form. Attaches to the `transition` property via the
  * universal `apply(CssValue)` overload.
  */
final case class Transition(
    property: String,
    duration: Time,
    timing: TimingFunction,
    delay: Option[Time],
) extends CssValue:
  def render: String =
    (List(property, duration.render, timing.render) ++ delay.map(_.render).toList).mkString(" ")

object Transition:

  /** A comma-joined stack of transitions — the value `transition: a, b, c` takes. */
  final case class Transitions(transitions: List[Transition]) extends CssValue:
    def render: String = transitions.map(_.render).mkString(", ")

  // typed-property constructors (preferred) — name comes from the catalog object, so it can't drift

  /** A transition on a TYPED property with the default `ease` timing and no delay: `Transition(Styles.color,
    * Time.s(0.2))`.
    */
  def apply(property: StylesFoundation.DS, duration: Time): Transition =
    Transition(property.property, duration, TimingFunction.ease, scala.None)

  /** A transition on a typed property with an explicit [[TimingFunction]] and no delay. */
  def apply(property: StylesFoundation.DS, duration: Time, timing: TimingFunction): Transition =
    Transition(property.property, duration, timing, scala.None)

  /** A transition on a typed property with timing + delay. */
  def apply(property: StylesFoundation.DS, duration: Time, timing: TimingFunction, delay: Time): Transition =
    Transition(property.property, duration, timing, Some(delay))

  // String constructors (escape hatch) — for `all`, custom properties, or names not in the catalog

  /** A transition naming the property by string, default `ease` timing, no delay (escape hatch — prefer the typed
    * overload).
    */
  def apply(property: String, duration: Time): Transition =
    Transition(property, duration, TimingFunction.ease, scala.None)

  /** A string-named transition with an explicit [[TimingFunction]] and no delay. */
  def apply(property: String, duration: Time, timing: TimingFunction): Transition =
    Transition(property, duration, timing, scala.None)

  /** Comma-join multiple property transitions into one value. */
  def list(transitions: Transition*): Transitions = Transitions(transitions.toList)
end Transition
