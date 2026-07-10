package ascent.css

import zio.test.*

/** The [[Image]] value type — `url(...)`, typed gradients, `image-set(...)`. */
object ImageSpec extends ZIOSpecDefault:

  def spec = suite("Image")(
    test("url quotes the href") {
      assertTrue(Image.url("/bg.png").render == """url("/bg.png")""")
    },
    test("a gradient used as an image renders the gradient") {
      val g = Gradient.linear(Angle.deg(90))(ColorStop(Color.transparent))
      assertTrue(Image.gradient(g).render == "linear-gradient(90.0deg, transparent)")
    },
    test("image-set comma-joins candidates") {
      assertTrue(Image.imageSet("\"a.png\" 1x", "\"a2.png\" 2x").render == "image-set(\"a.png\" 1x, \"a2.png\" 2x)")
    },
    test("attaches to background-image via the Imageish mixin (url + gradient)") {
      import Styles.*
      val g = Gradient.linear(Angle.deg(90))(ColorStop(Color.transparent))
      assertTrue(
        backgroundImage.url("/bg.png").render == """background-image: url("/bg.png");""",
        backgroundImage(g).render == "background-image: linear-gradient(90.0deg, transparent);",
      )
    },
  )
end ImageSpec
