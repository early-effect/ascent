package ascent.css

import zio.test.*

/** The [[Transform]] value type — typed `transform` functions (`translateY`, `scale`, `rotate`, …) composed from
  * [[Length]], [[Angle]], and plain numbers, with a space-joined multi-function form ([[Transform.list]]).
  */
object TransformSpec extends ZIOSpecDefault:

  def spec = suite("Transform")(
    test("translateY takes a Length") {
      assertTrue(Transform.translateY(Length.px(8)).render == "translateY(8.0px)")
    },
    test("scale takes a unitless number (platform-stable)") {
      assertTrue(
        Transform.scale(1).render == "scale(1.0)",
        Transform.scale(1.18).render == "scale(1.18)",
      )
    },
    test("rotate takes an Angle") {
      assertTrue(Transform.rotate(Angle.deg(90)).render == "rotate(90.0deg)")
    },
    test("translate takes x and y Lengths") {
      assertTrue(Transform.translate(Length.zero, Length.px(8)).render == "translate(0, 8.0px)")
    },
    test("Transform.list space-joins functions (the destroy-button rotate + scale)") {
      assertTrue(
        Transform.list(Transform.rotate(Angle.deg(90)), Transform.scale(1.1)).render == "rotate(90.0deg) scale(1.1)"
      )
    },
    test("a Transform attaches to the transform property via the universal overload") {
      assertTrue(Styles.transform(Transform.scale(1.15)).render == "transform: scale(1.15);")
    },
  )
end TransformSpec
