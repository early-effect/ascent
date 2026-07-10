package ascent.css

/** A composable CSS shadow — one layer of a `box-shadow` or `text-shadow`.
  *
  * Composes [[Length]] offsets/blur/spread and a [[Color]]. `spread` is optional because `text-shadow` has no spread
  * and box-shadows often omit it; `inset` leads the render when set. Use [[Shadow.list]] for the comma-joined
  * multi-layer form. Attaches to `box-shadow` / `text-shadow` via the universal `apply(CssValue)` overload.
  *
  * The 4-/5-arg [[Shadow.apply]] and [[Shadow.inset]] builders are the ergonomic surface; the full-field constructor
  * (with `spread: Option` and `inset: Boolean`) is available for power use.
  */
final case class Shadow(
    offsetX: Length,
    offsetY: Length,
    blur: Length,
    spread: Option[Length],
    color: Color,
    inset: Boolean,
) extends CssValue:
  def render: String =
    val core = (List(offsetX, offsetY, blur).map(_.render) ++ spread.map(_.render).toList :+ color.render)
      .mkString(" ")
    if inset then s"inset $core" else core
end Shadow

object Shadow:

  /** A comma-joined stack of shadow layers — the value `box-shadow: a, b, c` takes. */
  final case class Shadows(shadows: List[Shadow]) extends CssValue:
    def render: String = shadows.map(_.render).mkString(", ")

  /** A shadow with no spread: `offsetX offsetY blur color`. */
  def apply(offsetX: Length, offsetY: Length, blur: Length, color: Color): Shadow =
    Shadow(offsetX, offsetY, blur, scala.None, color, inset = false)

  /** A shadow with spread: `offsetX offsetY blur spread color`. */
  def apply(offsetX: Length, offsetY: Length, blur: Length, spread: Length, color: Color): Shadow =
    Shadow(offsetX, offsetY, blur, Some(spread), color, inset = false)

  /** An inset shadow with no spread. */
  def inset(offsetX: Length, offsetY: Length, blur: Length, color: Color): Shadow =
    Shadow(offsetX, offsetY, blur, scala.None, color, inset = true)

  /** An inset shadow with spread. */
  def inset(offsetX: Length, offsetY: Length, blur: Length, spread: Length, color: Color): Shadow =
    Shadow(offsetX, offsetY, blur, Some(spread), color, inset = true)

  /** Stack shadow layers into one comma-joined value. */
  def list(shadows: Shadow*): Shadows = Shadows(shadows.toList)
end Shadow
