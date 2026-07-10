package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `filter` / `backdrop-filter` function — `blur(24px)`, `brightness(1.08)`, `saturate(1.4)`, …
  *
  * Composes [[Length]] (blur radius), [[Angle]] (`hue-rotate`), and plain unitless multipliers (`brightness`,
  * `saturate`, `contrast`, … where `1.0` is the identity and `1.4` ≡ `140%`). Use [[Filter.list]] for the space-joined
  * multi-filter form. Attaches to `filter` / `backdrop-filter` via the universal `apply(CssValue)` overload.
  */
sealed trait Filter extends CssValue:
  override def toString: String = render

object Filter:

  /** A single `name(args)` filter function. */
  final case class Fn(name: String, args: String) extends Filter:
    def render: String = s"$name($args)"

  /** A space-joined sequence of filter functions — the value `filter: a b c` takes. */
  final case class Filters(fns: List[Filter]) extends Filter:
    def render: String = fns.map(_.render).mkString(" ")

  def blur(radius: Length): Filter       = Fn("blur", radius.render)
  def brightness(n: Double): Filter      = Fn("brightness", formatDouble(n))
  def contrast(n: Double): Filter        = Fn("contrast", formatDouble(n))
  def saturate(n: Double): Filter        = Fn("saturate", formatDouble(n))
  def grayscale(n: Double): Filter       = Fn("grayscale", formatDouble(n))
  def sepia(n: Double): Filter           = Fn("sepia", formatDouble(n))
  def invert(n: Double): Filter          = Fn("invert", formatDouble(n))
  def opacity(n: Double): Filter         = Fn("opacity", formatDouble(n))
  def hueRotate(angle: Angle): Filter    = Fn("hue-rotate", angle.render)
  def dropShadow(shadow: Shadow): Filter = Fn("drop-shadow", shadow.render)

  /** Compose multiple filter functions into one space-joined value. */
  def list(fns: Filter*): Filter = Filters(fns.toList)
end Filter
