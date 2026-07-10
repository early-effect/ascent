package ascent.css

/** A single stop in a gradient: a [[Color]] with an optional position along the gradient line. */
final case class ColorStop(color: Color, at: Option[Length]) extends CssValue:
  def render: String = at match
    case scala.None     => color.render
    case Some(position) => s"${color.render} ${position.render}"

object ColorStop:
  /** A stop with no explicit position — the browser distributes it evenly. */
  def apply(color: Color): ColorStop = ColorStop(color, scala.None)

  /** A positioned stop: `<color> <position>`. */
  def apply(color: Color, at: Length): ColorStop = ColorStop(color, Some(at))
end ColorStop

/** A `radial-gradient` shape keyword: `circle` or `ellipse`. */
enum RadialShape(val render: String):
  case Circle  extends RadialShape("circle")
  case Ellipse extends RadialShape("ellipse")

/** A `radial-gradient` size keyword (`<extent-keyword>`) — how far the gradient ray extends. */
enum RadialExtent(val render: String):
  case ClosestSide    extends RadialExtent("closest-side")
  case ClosestCorner  extends RadialExtent("closest-corner")
  case FarthestSide   extends RadialExtent("farthest-side")
  case FarthestCorner extends RadialExtent("farthest-corner")

/** The typed prelude of a `radial-gradient(...)` — `[ <shape> || <extent> ]? [ at <position> ]?`. Every part is
  * optional (a bare `radial-gradient(colors…)` is valid); present parts render space-joined with the position prefixed
  * by `at`. Build via the [[Gradient.radial]] overloads or [[RadialGeometry]]'s helpers.
  */
final case class RadialGeometry(
    shape: Option[RadialShape] = scala.None,
    extent: Option[RadialExtent] = scala.None,
    position: Option[Position] = scala.None,
):
  def render: String =
    val parts = List(
      shape.map(_.render),
      extent.map(_.render),
      position.map(p => s"at ${p.render}"),
    ).flatten
    parts.mkString(" ")
end RadialGeometry

object RadialGeometry:
  /** A circle centered at `position`: `circle at <x> <y>`. */
  def circleAt(position: Position): RadialGeometry =
    RadialGeometry(shape = Some(RadialShape.Circle), position = Some(position))

  /** An ellipse centered at `position`: `ellipse at <x> <y>`. */
  def ellipseAt(position: Position): RadialGeometry =
    RadialGeometry(shape = Some(RadialShape.Ellipse), position = Some(position))

/** A composable CSS gradient — `linear-gradient(...)`, `radial-gradient(...)`, or a comma-joined stack of them for
  * layered backgrounds.
  *
  * Composes [[Color]], [[Angle]], [[Length]], and [[Position]] through [[ColorStop]] / [[RadialGeometry]]. Attaches to
  * `background` / `background-image` via the universal `apply(CssValue)` overload.
  */
sealed trait Gradient extends CssValue:
  override def toString: String = render

object Gradient:

  /** `linear-gradient(<angle>, <stop>, …)`. */
  final case class Linear(angle: Angle, stops: List[ColorStop]) extends Gradient:
    def render: String = s"linear-gradient(${angle.render}, ${stops.map(_.render).mkString(", ")})"

  /** `radial-gradient(<prelude>, <stop>, …)` where `prelude` is the shape/size/position phrase, e.g.
    * `circle at 20% 0%`. The prelude is pre-rendered (typed via [[RadialGeometry]] or a raw string escape hatch); an
    * empty prelude omits the leading `<prelude>,` entirely.
    */
  final case class Radial(prelude: String, stops: List[ColorStop]) extends Gradient:
    def render: String =
      val stopsStr = stops.map(_.render).mkString(", ")
      if prelude.isEmpty then s"radial-gradient($stopsStr)"
      else s"radial-gradient($prelude, $stopsStr)"

  /** Multiple gradient layers comma-joined into one value, painted front-to-back. */
  final case class Layers(gradients: List[Gradient]) extends Gradient:
    def render: String = gradients.map(_.render).mkString(", ")

  /** Build a linear gradient. Requires at least one stop — a stop-less gradient is invalid CSS and a programmer error.
    */
  def linear(angle: Angle)(stops: ColorStop*): Gradient =
    require(stops.nonEmpty, "a gradient needs at least one color stop")
    Linear(angle, stops.toList)

  /** Build a radial gradient with a TYPED shape/size/position prelude: `radial(RadialGeometry.circleAt(Position.at(x,
    * y)))(stops…)` → `radial-gradient(circle at <x> <y>, …)`. Requires ≥1 stop.
    */
  def radial(geometry: RadialGeometry)(stops: ColorStop*): Gradient =
    require(stops.nonEmpty, "a gradient needs at least one color stop")
    Radial(geometry.render, stops.toList)

  /** Build a radial gradient with a raw shape/position prelude string (e.g. `"circle at 20% 0%"`) — the escape hatch
    * for radial geometries the typed [[RadialGeometry]] doesn't model. Requires ≥1 stop.
    */
  def radial(prelude: String)(stops: ColorStop*): Gradient =
    require(stops.nonEmpty, "a gradient needs at least one color stop")
    Radial(prelude, stops.toList)

  /** Stack gradient layers into one comma-joined background value. */
  def layers(gradients: Gradient*): Gradient = Layers(gradients.toList)
end Gradient
