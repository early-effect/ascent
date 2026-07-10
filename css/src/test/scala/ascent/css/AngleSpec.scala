package ascent.css

import zio.test.*

/** The [[Angle]] value type — a CSS `<angle>` (`deg`/`rad`/`turn`/`grad`) for gradient directions and rotate
  * transforms.
  */
object AngleSpec extends ZIOSpecDefault:

  def spec = suite("Angle")(
    test("each unit renders its suffix with platform-stable doubles") {
      assertTrue(
        Angle.deg(160).render == "160.0deg",
        Angle.rad(3.14).render == "3.14rad",
        Angle.turn(0.5).render == "0.5turn",
        Angle.grad(100).render == "100.0grad",
      )
    },
    test("toString == render for interpolation during migration") {
      assertTrue(Angle.deg(90).toString == "90.0deg")
    },
    test("an Angle attaches to a property via the universal overload") {
      assertTrue(Styles.rotate(Angle.deg(90)).render == "rotate: 90.0deg;")
    },
  )
end AngleSpec
