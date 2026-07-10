package ascent.css

/** A composable CSS `<basic-shape>` — `circle()`, `ellipse()`, `inset()`, `polygon()`, `path()`. The value type behind
  * `clip-path` and `shape-outside`.
  *
  * Attaches via the universal `apply(CssValue)` overload. `circle`/`ellipse` optionally center at a [[Position]];
  * `polygon` takes `"x y"` vertex strings (a pair of `<length-percentage>`s each); `inset` takes its edge insets as
  * [[Length]]s.
  */
sealed trait BasicShape extends CssValue:
  override def toString: String = render

object BasicShape:

  /** `circle(<radius>? [at <position>]?)`. */
  final case class Circle(radius: Option[String], at: Option[Position]) extends BasicShape:
    def render: String =
      val parts = radius.toList ++ at.map(p => s"at ${p.render}").toList
      s"circle(${parts.mkString(" ")})"

  /** `ellipse(<rx> <ry>? [at <position>]?)`. */
  final case class Ellipse(radii: Option[(String, String)], at: Option[Position]) extends BasicShape:
    def render: String =
      val parts = radii.map((rx, ry) => s"$rx $ry").toList ++ at.map(p => s"at ${p.render}").toList
      s"ellipse(${parts.mkString(" ")})"

  /** `inset(<top> <right>? <bottom>? <left>?)` — 1–4 edge insets. */
  final case class Inset(edges: List[Length]) extends BasicShape:
    def render: String = s"inset(${edges.map(_.render).mkString(" ")})"

  /** `polygon(<x y>, …)` — a list of vertex points, each `"x y"`. */
  final case class Polygon(points: List[String]) extends BasicShape:
    def render: String = s"polygon(${points.mkString(", ")})"

  /** `path("<svg-path-data>")`. */
  final case class Path(data: String) extends BasicShape:
    def render: String = s"""path("$data")"""

  /** `circle()` — default radius (closest-side), centered. */
  def circle: BasicShape = Circle(scala.None, scala.None)

  /** `circle(<radius> at <position>)`. */
  def circle(radius: String, at: Position): BasicShape = Circle(Some(radius), Some(at))

  /** `ellipse(<rx> <ry> at <position>)`. */
  def ellipse(rx: String, ry: String, at: Position): BasicShape = Ellipse(Some((rx, ry)), Some(at))

  /** `inset(...)` from 1–4 edge insets. */
  def inset(edges: Length*): BasicShape = Inset(edges.toList)

  /** `polygon("x y", "x y", …)`. */
  def polygon(points: String*): BasicShape = Polygon(points.toList)

  /** `path("...")` from SVG path data. */
  def path(data: String): BasicShape = Path(data)
end BasicShape
