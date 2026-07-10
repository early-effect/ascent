package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `<time>` — `0.2s`, `150ms`.
  *
  * Feeds [[Transition]] durations and delays, where the time is a sub-value of a multi-token transition and a bare
  * property method can't reach. Attaches to any property via the universal `apply(CssValue)` overload (e.g.
  * `transition-duration`).
  */
sealed trait Time extends CssValue:
  override def toString: String = render

object Time:
  final case class Unit(n: Double, suffix: String) extends Time:
    def render: String = s"${formatDouble(n)}$suffix"

  def s(n: Double): Time  = Unit(n, "s")
  def ms(n: Double): Time = Unit(n, "ms")
end Time
