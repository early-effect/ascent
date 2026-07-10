package ascent.css

/** The value of the legacy `clip` property — `rect(<top>, <right>, <bottom>, <left>)` where each edge is a `<length>`
  * or `auto`. `clip` is deprecated in favour of `clip-path`, but it's still the only property using this `rect()`
  * shape, so it gets a focused typed value rather than a raw string.
  *
  * Each edge is a [[Length]]; use [[Length.auto]] for the `auto` keyword (the CSS2 grammar allows it per edge).
  */
final case class Clip(top: Length, right: Length, bottom: Length, left: Length) extends CssValue:
  def render: String = s"rect(${top.render}, ${right.render}, ${bottom.render}, ${left.render})"

object Clip:
  /** `rect(top, right, bottom, left)` — the four clip-region edges. */
  def rect(top: Length, right: Length, bottom: Length, left: Length): Clip = Clip(top, right, bottom, left)
