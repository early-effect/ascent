package ascent.css

/** A CSS `<line-style>` keyword — the style component of a border/outline/column-rule. */
enum LineStyle(val render: String) extends CssValue:
  case None   extends LineStyle("none")
  case Hidden extends LineStyle("hidden")
  case Dotted extends LineStyle("dotted")
  case Dashed extends LineStyle("dashed")
  case Solid  extends LineStyle("solid")
  case Double extends LineStyle("double")
  case Groove extends LineStyle("groove")
  case Ridge  extends LineStyle("ridge")
  case Inset  extends LineStyle("inset")
  case Outset extends LineStyle("outset")

  override def toString: String = render
end LineStyle

/** A composable CSS `border` shorthand value — `<line-width> || <line-style> || <color>`.
  *
  * Replaces stringly `border("1px solid #ccc")` with `Border(1.px, LineStyle.Solid, color)`. All three parts are
  * optional (the CSS grammar is "one or more, in any order"); a missing part is simply omitted from the rendered order
  * `width style color`. Feeds `border`, `border-top`/`-right`/`-bottom`/`-left`, the logical `border-block`/`-inline`
  * shorthands, `outline`, and `column-rule`. Attaches via the universal `apply(CssValue)`.
  */
final case class Border(
    width: Option[Length] = scala.None,
    style: Option[LineStyle] = scala.None,
    color: Option[Color] = scala.None,
) extends CssValue:
  def render: String =
    val parts = width.map(_.render).toList ++ style.map(_.render).toList ++ color.map(_.render).toList
    parts.mkString(" ")

  /** This border with a different width / style / color (ergonomic chaining). */
  def withWidth(w: Length): Border    = copy(width = Some(w))
  def withStyle(s: LineStyle): Border = copy(style = Some(s))
  def withColor(c: Color): Border     = copy(color = Some(c))
end Border

object Border:

  /** The full `width style color` triple — the everyday case. */
  def apply(width: Length, style: LineStyle, color: Color): Border =
    Border(Some(width), Some(style), Some(color))

  /** Just a style (`border-style`-like). */
  def apply(style: LineStyle): Border = Border(style = Some(style))

  /** Width + style (no color — inherits `currentColor`). */
  def apply(width: Length, style: LineStyle): Border = Border(Some(width), Some(style), scala.None)

  /** `solid` shorthand: `<width> solid <color>`. */
  def solid(width: Length, color: Color): Border = Border(Some(width), Some(LineStyle.Solid), Some(color))

  /** `dashed` shorthand: `<width> dashed <color>`. */
  def dashed(width: Length, color: Color): Border = Border(Some(width), Some(LineStyle.Dashed), Some(color))

  /** `none` — no border. */
  val none: Border = Border(style = Some(LineStyle.None))
end Border
