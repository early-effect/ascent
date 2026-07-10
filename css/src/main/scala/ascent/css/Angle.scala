package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `<angle>` — `160deg`, `0.5turn`, `3.14rad`, `100grad`.
  *
  * Feeds gradient directions ([[LinearGradient]]) and rotate transforms, where an angle is a sub-value of a larger
  * function and a bare property method can't reach. Attaches to any property via the universal `apply(CssValue)`
  * overload (e.g. the standalone `rotate` property).
  */
sealed trait Angle extends CssValue:
  override def toString: String = render

object Angle:
  final case class Unit(n: Double, suffix: String) extends Angle:
    def render: String = s"${formatDouble(n)}$suffix"

  def deg(n: Double): Angle  = Unit(n, "deg")
  def rad(n: Double): Angle  = Unit(n, "rad")
  def turn(n: Double): Angle = Unit(n, "turn")
  def grad(n: Double): Angle = Unit(n, "grad")
end Angle
