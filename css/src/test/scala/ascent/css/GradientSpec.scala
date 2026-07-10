package ascent.css

import zio.test.*

import scala.util.Try

/** The [[Gradient]] value type + [[ColorStop]] — typed `linear-gradient` / `radial-gradient` composed from [[Color]],
  * [[Angle]], and [[Length]], plus a comma-joined multi-layer form.
  */
object GradientSpec extends ZIOSpecDefault:

  def spec = suite("Gradient")(
    suite("ColorStop")(
      test("a stop with no position renders just the color") {
        assertTrue(ColorStop(Color.transparent).render == "transparent")
      },
      test("a positioned stop renders `<color> <position>`") {
        assertTrue(ColorStop(Color.hex("#0a0418"), Length.pct(60)).render == "rgb(10, 4, 24) 60.0%")
      },
    ),
    suite("linear-gradient")(
      test("angle + hex stops (the pageGradient base layer)") {
        val g = Gradient.linear(Angle.deg(160))(
          ColorStop(Color.hex("#0a0418"), Length.pct(0)),
          ColorStop(Color.hex("#1a1129"), Length.pct(60)),
          ColorStop(Color.hex("#08020e"), Length.pct(100)),
        )
        assertTrue(
          g.render == "linear-gradient(160.0deg, rgb(10, 4, 24) 0.0%, rgb(26, 17, 41) 60.0%, rgb(8, 2, 14) 100.0%)"
        )
      }
    ),
    suite("radial-gradient")(
      test("shape prelude + color stops (a pageGradient radial layer)") {
        val g = Gradient.radial("circle at 20% 0%")(
          ColorStop(Color.hex("#ff00aa").alpha(0.18), Length.pct(0)),
          ColorStop(Color.transparent, Length.pct(45)),
        )
        assertTrue(g.render == "radial-gradient(circle at 20% 0%, rgb(255 0 170 / 0.18) 0.0%, transparent 45.0%)")
      },
      test("TYPED RadialGeometry prelude renders the same as the equivalent string") {
        val g = Gradient.radial(RadialGeometry.circleAt(Position.at(20.pct, 0.pct)))(
          ColorStop(Color.hex("#ff00aa").alpha(0.18), Length.pct(0)),
          ColorStop(Color.transparent, Length.pct(45)),
        )
        assertTrue(g.render == "radial-gradient(circle at 20.0% 0.0%, rgb(255 0 170 / 0.18) 0.0%, transparent 45.0%)")
      },
      test("RadialGeometry renders shape || extent || at-position; empty geometry omits the prelude") {
        assertTrue(
          RadialGeometry(shape = Some(RadialShape.Ellipse), extent = Some(RadialExtent.FarthestCorner)).render
            == "ellipse farthest-corner",
          RadialGeometry.ellipseAt(Position.center).render == "ellipse at center",
          Gradient.radial(RadialGeometry())(ColorStop(Color.transparent)).render
            == "radial-gradient(transparent)",
        )
      },
    ),
    suite("layers")(
      test("multiple gradients comma-join into one background value (the full pageGradient)") {
        val g = Gradient.layers(
          Gradient.radial("circle at 20% 0%")(ColorStop(Color.transparent, Length.pct(45))),
          Gradient.linear(Angle.deg(160))(ColorStop(Color.hex("#08020e"))),
        )
        assertTrue(
          g.render == "radial-gradient(circle at 20% 0%, transparent 45.0%), linear-gradient(160.0deg, rgb(8, 2, 14))"
        )
      }
    ),
    suite("pathological")(
      test("a gradient with no stops is a programmer error (throws)") {
        assertTrue(
          Try(Gradient.linear(Angle.deg(0))()).isFailure,
          Try(Gradient.radial("circle")()).isFailure,
        )
      }
    ),
    test("a Gradient attaches to a property via the universal overload") {
      val g = Gradient.linear(Angle.deg(90))(ColorStop(Color.transparent))
      assertTrue(Styles.backgroundImage(g).render == "background-image: linear-gradient(90.0deg, transparent);")
    },
  )
end GradientSpec
