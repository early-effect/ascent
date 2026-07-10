package ascent.css

/** A composable CSS `<position>` — a point in a box: a keyword corner/edge (`center`, `top left`), a
  * `<length-percentage>` pair, or an edge-offset form. Feeds `background-position`, `object-position`, gradient
  * positions, `mask-position`, transform-origin, etc.
  *
  * Attaches via the universal `apply(CssValue)` overload. The keyword corners are `val`s; [[Position.at]] takes a typed
  * x/y [[Length]] pair (lengths or percentages).
  */
sealed trait Position extends CssValue:
  override def toString: String = render

object Position:

  /** A keyword or pre-rendered position phrase (`center`, `top left`, `bottom right`). */
  final case class Keyword(rendered: String) extends Position:
    def render: String = rendered

  /** An explicit `x y` pair of `<length-percentage>`s. */
  final case class Xy(x: Length, y: Length) extends Position:
    def render: String = s"${x.render} ${y.render}"

  val center: Position      = Keyword("center")
  val top: Position         = Keyword("top")
  val right: Position       = Keyword("right")
  val bottom: Position      = Keyword("bottom")
  val left: Position        = Keyword("left")
  val topLeft: Position     = Keyword("top left")
  val topRight: Position    = Keyword("top right")
  val bottomLeft: Position  = Keyword("bottom left")
  val bottomRight: Position = Keyword("bottom right")

  /** An explicit `x y` position from a typed length/percentage pair. */
  def at(x: Length, y: Length): Position = Xy(x, y)
end Position
