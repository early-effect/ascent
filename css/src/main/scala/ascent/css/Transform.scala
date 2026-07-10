package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `transform` function — `translateY(8px)`, `scale(1.1)`, `rotate(90deg)`, …
  *
  * Composes [[Length]] (translate offsets), [[Angle]] (rotations), and plain numbers (scales). Use [[Transform.list]]
  * for the space-joined multi-function form CSS `transform` takes. Attaches to the `transform` property via the
  * universal `apply(CssValue)` overload.
  */
sealed trait Transform extends CssValue:
  override def toString: String = render

object Transform:

  /** A single `name(args)` transform function. */
  final case class Fn(name: String, args: String) extends Transform:
    def render: String = s"$name($args)"

  /** A space-joined sequence of transform functions — the value `transform: a b c` takes. */
  final case class Transforms(fns: List[Transform]) extends Transform:
    def render: String = fns.map(_.render).mkString(" ")

  def translate(x: Length, y: Length): Transform = Fn("translate", s"${x.render}, ${y.render}")
  def translateX(x: Length): Transform           = Fn("translateX", x.render)
  def translateY(y: Length): Transform           = Fn("translateY", y.render)
  def scale(n: Double): Transform                = Fn("scale", formatDouble(n))
  def scale(x: Double, y: Double): Transform     = Fn("scale", s"${formatDouble(x)}, ${formatDouble(y)}")
  def scaleX(x: Double): Transform               = Fn("scaleX", formatDouble(x))
  def scaleY(y: Double): Transform               = Fn("scaleY", formatDouble(y))
  def rotate(a: Angle): Transform                = Fn("rotate", a.render)
  def skew(x: Angle, y: Angle): Transform        = Fn("skew", s"${x.render}, ${y.render}")

  /** Compose multiple transform functions into one space-joined value. */
  def list(fns: Transform*): Transform = Transforms(fns.toList)
end Transform
