package ascent.css

import zio.test.*

import scala.util.Try

/** The [[Color]] value type: a composable sealed ADT for CSS colors, rendering CSS Color 4 form (opaque `rgb(r, g, b)`,
  * alpha `rgb(r g b / a)`).
  */
object ColorSpec extends ZIOSpecDefault:

  def spec = suite("Color")(
    suite("hex parsing")(
      test("6-digit hex normalizes to opaque rgb") {
        assertTrue(Color.hex("#ff00aa").render == "rgb(255, 0, 170)")
      },
      test("leading # is optional") {
        assertTrue(Color.hex("ff00aa").render == "rgb(255, 0, 170)")
      },
      test("3-digit hex expands each nibble") {
        assertTrue(Color.hex("#f0a").render == "rgb(255, 0, 170)")
      },
      test("8-digit hex carries the trailing byte as alpha") {
        // 0x80 = 128; 128/255 rounded to 3 dp = 0.502.
        assertTrue(Color.hex("#ff00aa80").render == "rgb(255 0 170 / 0.502)")
      },
      test("4-digit hex expands nibbles incl. alpha") {
        // alpha nibble 8 -> 0x88 = 136; 136/255 rounded = 0.533.
        assertTrue(Color.hex("#f0a8").render == "rgb(255 0 170 / 0.533)")
      },
      test("malformed hex throws IllegalArgumentException") {
        assertTrue(
          Try(Color.hex("#xyz")).isFailure,
          Try(Color.hex("#12345")).isFailure,
          Try(Color.hex("nope")).isFailure,
          Try(Color.hex("#")).isFailure,
        )
      },
    ),
    suite("constructors + clamping")(
      test("rgb clamps channels to 0..255") {
        assertTrue(Color.rgb(300, -5, 0).render == "rgb(255, 0, 0)")
      },
      test("rgba clamps alpha >1 to opaque (no alpha rendered)") {
        assertTrue(Color.rgba(0, 0, 0, 1.5).render == "rgb(0, 0, 0)")
      },
      test("rgba with sub-1 alpha renders the slash-alpha form") {
        assertTrue(Color.rgba(255, 0, 170, 0.65).render == "rgb(255 0 170 / 0.65)")
      },
    ),
    suite("derivation — the design-token win")(
      test("alpha on a hex color renders the slash-alpha form") {
        assertTrue(
          Color.hex("#ff00aa").alpha(0.65).render == "rgb(255 0 170 / 0.65)",
          Color.hex("#ff00aa").alpha(0.35).render == "rgb(255 0 170 / 0.35)",
        )
      },
      test("alpha clamps below 0") {
        assertTrue(Color.rgb(0, 0, 0).alpha(-0.2).render == "rgb(0 0 0 / 0.0)")
      },
      test("lighten adds lightness in HSL space") {
        assertTrue(Color.hex("#ff00aa").lighten(0.1).render == "hsl(320, 100.0%, 60.0%)")
      },
      test("darken subtracts lightness in HSL space") {
        assertTrue(Color.hex("#ff00aa").darken(0.1).render == "hsl(320, 100.0%, 40.0%)")
      },
      test("mix blends per-channel in sRGB (black + white at 0.5 = mid grey)") {
        assertTrue(Color.rgb(0, 0, 0).mix(Color.rgb(255, 255, 255), 0.5).render == "rgb(128, 128, 128)")
      },
    ),
    suite("hsl / hsla")(
      test("hsl renders comma form when opaque") {
        assertTrue(Color.hsl(320, 100, 50).render == "hsl(320, 100.0%, 50.0%)")
      },
      test("hsla renders slash-alpha form") {
        assertTrue(Color.hsla(320, 100, 50, 0.5).render == "hsl(320 100.0% 50.0% / 0.5)")
      },
    ),
    suite("keyword colors")(
      test("a keyword renders verbatim") {
        assertTrue(
          Color.keyword("rebeccapurple").render == "rebeccapurple",
          Color.transparent.render == "transparent",
          Color.currentColor.render == "currentColor",
        )
      },
      test("derivation on a keyword degrades to self (keywords carry no channels to derive from)") {
        assertTrue(
          Color.currentColor.alpha(0.5).render == "currentColor",
          Color.keyword("red").lighten(0.1).render == "red",
        )
      },
    ),
    suite("toString == render (so string interpolation stays valid CSS during migration)")(
      test("opaque") {
        assertTrue(Color.hex("#ff00aa").toString == "rgb(255, 0, 170)")
      },
      test("with alpha") {
        assertTrue(Color.hex("#ff00aa").alpha(0.65).toString == "rgb(255 0 170 / 0.65)")
      },
    ),
    suite("attaching a Color to a property via the universal overload")(
      test("color(c) renders `name: <color>;`") {
        assertTrue(Styles.color(Color.hex("#ff00aa")).render == "color: rgb(255, 0, 170);")
      },
      test("a bare-DS property (background) accepts a Color too") {
        assertTrue(Styles.background(Color.transparent).render == "background: transparent;")
      },
    ),
  )
end ColorSpec
