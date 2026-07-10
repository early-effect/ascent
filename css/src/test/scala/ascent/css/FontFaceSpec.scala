package ascent.css

import zio.*
import zio.test.*

/** `@font-face` is a standalone top-level descriptor block, keyed for installation by its `font-family` value. */
object FontFaceSpec extends ZIOSpecDefault:

  def spec = suite("FontFace")(
    suite("rendering")(
      test("renders as `@font-face { <descriptors>; }` with no parent selector") {
        val ff = FontFace(
          Declaration("font-family", "\"MySerif\""),
          Declaration("src", "url(/fonts/myserif.woff2) format(\"woff2\")"),
          Declaration("font-weight", "400"),
        )
        val rendered = ff.render
        assertTrue(
          rendered.startsWith("@font-face {"),
          rendered.contains("font-family: \"MySerif\";"),
          rendered.contains("src: url(/fonts/myserif.woff2) format(\"woff2\");"),
          rendered.contains("font-weight: 400;"),
          rendered.trim.endsWith("}"),
        )
      },
      test("accepts typed Declarations from the generated Styles catalog (no duplication)") {
        val ff = FontFace(
          Styles.fontWeight(400),
          Styles.fontStyle.italic,
        )
        val rendered = ff.render
        assertTrue(
          rendered.contains("@font-face {"),
          rendered.contains("font-weight: 400;"),
          rendered.contains("font-style: italic;"),
        )
      },
    ),
    suite("FontFaceDescriptors generated catalog")(
      test("`src` descriptor produces a Declaration carrying a url(...) format(...) value") {
        val d = FontFaceDescriptors.src.url("/fonts/serif.woff2", format = "woff2")
        assertTrue(
          d.name == "src",
          d.value == "url(\"/fonts/serif.woff2\") format(\"woff2\")",
        )
      },
      test("`unicode-range` descriptor accepts a raw range string") {
        val d = FontFaceDescriptors.unicodeRange("U+0000-00FF")
        assertTrue(d.name == "unicode-range", d.value == "U+0000-00FF")
      },
      test("`size-adjust` descriptor accepts a percentage via the typed Percent mixin") {
        assertTrue(FontFaceDescriptors.sizeAdjust.pct(110.0).render == "size-adjust: 110.0%;")
      },
      test("`font-display` lives in FontFaceDescriptors (it is @font-face-only — not a general CSS property)") {
        assertTrue(
          FontFaceDescriptors.fontDisplay.auto.render == "font-display: auto;",
          FontFaceDescriptors.fontDisplay.block.render == "font-display: block;",
          FontFaceDescriptors.fontDisplay.swap.render == "font-display: swap;",
          FontFaceDescriptors.fontDisplay.fallback.render == "font-display: fallback;",
          FontFaceDescriptors.fontDisplay.optional.render == "font-display: optional;",
        )
      },
    ),
    suite("StyleSink integration")(
      test("installInto registers the @font-face block under a key derived from font-family") {
        for
          sink <- StyleSink.capturing
          ff = FontFace(
            Declaration("font-family", "\"MySerif\""),
            Declaration("src", "url(/serif.woff2)"),
          )
          _     <- ff.installInto(sink)
          rules <- sink.captured
        yield assertTrue(
          rules.size == 1,
          rules.head._1.contains("MySerif"),
          rules.head._2.contains("@font-face {"),
          rules.head._2.contains("font-family: \"MySerif\";"),
        )
      },
      test("installing the same family twice is idempotent (replace, not duplicate)") {
        for
          sink <- StyleSink.capturing
          ff1 = FontFace(Declaration("font-family", "\"X\""), Declaration("src", "url(a)"))
          ff2 = FontFace(Declaration("font-family", "\"X\""), Declaration("src", "url(b)"))
          _     <- ff1.installInto(sink)
          _     <- ff2.installInto(sink)
          rules <- sink.captured
        yield assertTrue(rules.size == 1, rules.head._2.contains("url(b)"))
      },
    ),
  )
end FontFaceSpec
