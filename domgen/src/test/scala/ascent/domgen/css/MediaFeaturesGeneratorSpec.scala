package ascent.domgen.css

import zio.*
import zio.test.*

/** [[MediaFeaturesGenerator]] reads `@media` descriptors and emits the `ascent.css.MediaFeatures` trait. */
object MediaFeaturesGeneratorSpec extends ZIOSpecDefault:

  private val sampleAtRule = WebrefCss.AtRule(
    name = "@media",
    descriptors = List(
      WebrefCss.Property("orientation", value = Some("portrait | landscape")),
      WebrefCss.Property("hover", value = Some("none | hover")),
      WebrefCss.Property("prefers-reduced-motion", value = Some("no-preference | reduce")),
      WebrefCss.Property(
        "display-mode",
        value = Some("fullscreen | standalone | minimal-ui | browser | picture-in-picture"),
      ),
      WebrefCss.Property("width", value = Some("<length>")),
      WebrefCss.Property("monochrome", value = Some("<integer>")),
      WebrefCss.Property("aspect-ratio", value = Some("<ratio>")),
      WebrefCss.Property("resolution", value = Some("<resolution> | infinite")),
      WebrefCss.Property("-webkit-device-pixel-ratio", value = Some("<number>")), // vendor-prefixed: must be skipped
      WebrefCss.Property("brokenfeature", value = Some("[]][[")),                 // unparseable grammar: must not crash
    ),
  )

  private val source = MediaFeaturesGenerator.generate(sampleAtRule)

  def spec = suite("MediaFeaturesGenerator")(
    suite("file shape")(
      test("emits an AUTO-GENERATED header and a `trait MediaFeatures:` opener") {
        assertTrue(
          source.contains("AUTO-GENERATED"),
          source.contains("package ascent.css"),
          source.contains("import MediaFoundation.*"),
          source.contains("trait MediaFeatures:"),
        )
      },
      test("does NOT emit a feature for vendor-prefixed descriptors") {
        assertTrue(!source.contains("WebkitDevicePixelRatio"))
      },
      test("an unparseable grammar still produces a bare object with a `// could not parse` marker — no crash") {
        assertTrue(
          source.contains("// could not parse"),
          source.contains("""object brokenfeature extends MF("brokenfeature")"""),
        )
      },
    ),
    suite("pure-keyword features")(
      test("orientation emits two named constants and NO MF traits") {
        assertTrue(
          source.contains("""object orientation extends MF("orientation")"""),
          source.contains("val portrait"),
          source.contains("val landscape"),
          !source.contains("orientation extends MF(\"orientation\") with"),
        )
      },
      test("hyphenated keyword `no-preference` is camelCased to `noPreference` (CSS source stays hyphenated)") {
        assertTrue(
          source.contains("val noPreference"),
          source.contains("\"no-preference\""),
        )
      },
      test("display-mode covers all five enumerated values, multi-hyphen camelCased correctly") {
        assertTrue(
          source.contains("val fullscreen"),
          source.contains("val standalone"),
          source.contains("val minimalUi"),
          source.contains("val browser"),
          source.contains("val pictureInPicture"),
          source.contains("\"minimal-ui\""),
          source.contains("\"picture-in-picture\""),
        )
      },
    ),
    suite("typed-value features")(
      test("`width: <length>` produces width AND min-/max- siblings (range descriptor extension)") {
        // The @media grammar applies min-/max- uniformly to range features; webref doesn't enumerate
        // them per feature, so the generator infers them from the typed mixin.
        assertTrue(
          source.contains("""object width extends MF("width") with Length"""),
          source.contains("""object minWidth extends MF("min-width") with Length"""),
          source.contains("""object maxWidth extends MF("max-width") with Length"""),
        )
      },
      test("`monochrome: <integer>` produces a Numeric mixin AND min-/max- siblings") {
        assertTrue(
          source.contains("""object monochrome extends MF("monochrome") with Numeric"""),
          source.contains("""object minMonochrome extends MF("min-monochrome") with Numeric"""),
          source.contains("""object maxMonochrome extends MF("max-monochrome") with Numeric"""),
        )
      },
      test("`aspect-ratio: <ratio>` produces a Ratio mixin AND min-/max- siblings") {
        assertTrue(
          source.contains("""object aspectRatio extends MF("aspect-ratio") with Ratio"""),
          source.contains("""object minAspectRatio extends MF("min-aspect-ratio") with Ratio"""),
          source.contains("""object maxAspectRatio extends MF("max-aspect-ratio") with Ratio"""),
        )
      },
    ),
    suite("mixed (typed + keyword) features")(
      test("`resolution: <resolution> | infinite` produces both a Resolution trait AND an `infinite` constant") {
        assertTrue(
          source.contains("""object resolution extends MF("resolution") with Resolution"""),
          source.contains("val infinite"),
          // min-/max- siblings still apply but carry no keyword constants (`min-resolution: infinite` is nonsensical).
          source.contains("""object minResolution extends MF("min-resolution") with Resolution"""),
          source.contains("""object maxResolution extends MF("max-resolution") with Resolution"""),
        )
      }
    ),
    suite("Catalog driver")(
      test("generateFromCatalog groups @media partials by descriptor, keeps the one with a grammar, and dedupes") {
        val cat = WebrefCss.Catalog(
          properties = Nil,
          atrules = List(
            WebrefCss.AtRule("@media", List(WebrefCss.Property("hover", value = Some("none | hover")))),
            WebrefCss.AtRule("@media", List(WebrefCss.Property("hover", value = scala.None))), // partial: no grammar
            WebrefCss.AtRule("@supports", List(WebrefCss.Property("ignored", value = Some("any")))),
          ),
          values = Nil,
          selectors = Nil,
        )
        val src = MediaFeaturesGenerator.generateFromCatalog(cat)
        assertTrue(
          src.contains("trait MediaFeatures:"),
          src.contains("""object hover extends MF("hover")"""),
          src.split("object hover ").length == 2, // exactly one definition (split→2 chunks)
          !src.contains("ignored"),               // @supports descriptors didn't leak in
        )
      }
    ),
  )
end MediaFeaturesGeneratorSpec
