package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `<easing-function>` — the timing curve for `transition-timing-function`,
  * `animation-timing-function`, and the `transition`/`animation` shorthands.
  *
  * Covers the keyword curves (`ease`, `linear`, `step-start`, …) plus the two parameterised forms `cubic-bezier(...)`
  * and `steps(...)`. Hand-authored (not generated) because webref splits the keywords across `<cubic-bezier-…>` /
  * `<step-…>` sub-types and re-declares `<easing-function>` without them — and because `cubicBezier`/`steps` want typed
  * builders, not flattened keywords. Attaches via the universal `apply(CssValue)` overload.
  */
sealed trait TimingFunction extends CssValue:
  override def toString: String = render

object TimingFunction:

  /** A keyword curve (`ease`, `linear`, `ease-in-out`, `step-start`, `step-end`). */
  final case class Keyword(name: String) extends TimingFunction:
    def render: String = name

  /** `cubic-bezier(x1, y1, x2, y2)` — the control points of the curve. */
  final case class CubicBezier(x1: Double, y1: Double, x2: Double, y2: Double) extends TimingFunction:
    def render: String =
      s"cubic-bezier(${formatDouble(x1)}, ${formatDouble(y1)}, ${formatDouble(x2)}, ${formatDouble(y2)})"

  /** `steps(n, <jump>)` — a stepped curve. `jump` defaults to `end` (the bare `steps(n)` form). */
  final case class Steps(n: Int, jump: String) extends TimingFunction:
    def render: String = if jump.isEmpty then s"steps($n)" else s"steps($n, $jump)"

  val ease: TimingFunction      = Keyword("ease")
  val linear: TimingFunction    = Keyword("linear")
  val easeIn: TimingFunction    = Keyword("ease-in")
  val easeOut: TimingFunction   = Keyword("ease-out")
  val easeInOut: TimingFunction = Keyword("ease-in-out")
  val stepStart: TimingFunction = Keyword("step-start")
  val stepEnd: TimingFunction   = Keyword("step-end")

  def cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double): TimingFunction = CubicBezier(x1, y1, x2, y2)

  /** `steps(n)` — equivalent to `steps(n, end)`. */
  def steps(n: Int): TimingFunction = Steps(n, "")

  /** `steps(n, jumpStart | jumpEnd | jumpNone | jumpBoth | start | end)`. */
  def steps(n: Int, jump: String): TimingFunction = Steps(n, jump)
end TimingFunction
