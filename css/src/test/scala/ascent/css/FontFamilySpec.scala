package ascent.css

import zio.test.*

/** The [[FontFamily]] value type — a prioritized stack with auto-quoting + generic fallbacks. */
object FontFamilySpec extends ZIOSpecDefault:

  def spec = suite("FontFamily")(
    test("a bare identifier name is NOT quoted") {
      assertTrue(FontFamily.named("Inter").render == "Inter")
    },
    test("a name with spaces IS quoted") {
      assertTrue(FontFamily.named("Times New Roman").render == "\"Times New Roman\"")
    },
    test("an already-quoted name is left as-is") {
      assertTrue(FontFamily.named("'Orbitron'").render == "'Orbitron'")
    },
    test("generic families are unquoted keywords") {
      assertTrue(
        FontFamily.sansSerif.render == "sans-serif",
        FontFamily.systemUi.render == "system-ui",
      )
    },
    test("of(...) builds a comma-joined stack, generic last") {
      val stack = FontFamily.of(FontFamily.named("Inter"), FontFamily.systemUi, FontFamily.sansSerif)
      assertTrue(stack.render == "Inter, system-ui, sans-serif")
    },
    test("of(String, String*) auto-quotes each raw name") {
      assertTrue(FontFamily.of("Inter", "Times New Roman").render == "Inter, \"Times New Roman\"")
    },
    test("attaches to font-family via the FontFamilyish mixin") {
      import Styles.*
      val v = FontFamily.of(FontFamily.named("Inter"), FontFamily.sansSerif)
      assertTrue(fontFamily(v).render == "font-family: Inter, sans-serif;")
    },
  )
end FontFamilySpec
