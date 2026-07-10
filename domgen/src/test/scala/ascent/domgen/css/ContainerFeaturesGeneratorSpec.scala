package ascent.domgen.css

import zio.*
import zio.test.*

/** [[ContainerFeaturesGenerator]] is a thin `@container` facade over [[AtRuleFeaturesGenerator]] (the generic pipeline
  * is exercised by [[MediaFeaturesGeneratorSpec]]); this spec pins the Container-specific naming + filtering.
  */
object ContainerFeaturesGeneratorSpec extends ZIOSpecDefault:

  private val sampleAtRule = WebrefCss.AtRule(
    name = "@container",
    descriptors = List(
      WebrefCss.Property("width", value = Some("<length>")),
      WebrefCss.Property("inline-size", value = Some("<length>")),
      WebrefCss.Property("aspect-ratio", value = Some("<ratio>")),
      WebrefCss.Property("orientation", value = Some("portrait | landscape")),
    ),
  )

  def spec = suite("ContainerFeaturesGenerator")(
    test("emits a `trait ContainerFeatures` referencing ContainerFoundation, CF, and ContainerCondition") {
      val src = ContainerFeaturesGenerator.generate(sampleAtRule)
      assertTrue(
        src.contains("AUTO-GENERATED"),
        src.contains("trait ContainerFeatures:"),
        src.contains("import ContainerFoundation.*"),
        src.contains("""object width extends CF("width") with Length"""),
        src.contains("""object inlineSize extends CF("inline-size") with Length"""),
        src.contains("""object aspectRatio extends CF("aspect-ratio") with Ratio"""),
        src.contains("""object orientation extends CF("orientation")"""),
        // keyword constants reference ContainerCondition (not MediaCondition)
        src.contains("ContainerCondition.Keyword"),
        !src.contains("MediaCondition"),
      )
    },
    test("range descriptors get min-/max- siblings (the @container grammar layer)") {
      val src = ContainerFeaturesGenerator.generate(sampleAtRule)
      assertTrue(
        src.contains("""object minWidth extends CF("min-width") with Length"""),
        src.contains("""object maxWidth extends CF("max-width") with Length"""),
        src.contains("""object minInlineSize extends CF("min-inline-size") with Length"""),
        src.contains("""object maxInlineSize extends CF("max-inline-size") with Length"""),
        src.contains("""object minAspectRatio extends CF("min-aspect-ratio") with Ratio"""),
      )
    },
    test("generateFromCatalog filters to @container; @media descriptors do NOT leak in") {
      val cat = WebrefCss.Catalog(
        properties = Nil,
        atrules = List(
          WebrefCss.AtRule("@container", List(WebrefCss.Property("width", value = Some("<length>")))),
          // A neighbouring @media at-rule that must be filtered out.
          WebrefCss.AtRule(
            "@media",
            List(
              WebrefCss.Property("prefers-reduced-motion", value = Some("no-preference | reduce"))
            ),
          ),
        ),
        values = Nil,
        selectors = Nil,
      )
      val src = ContainerFeaturesGenerator.generateFromCatalog(cat)
      assertTrue(
        src.contains("trait ContainerFeatures:"),
        src.contains("""object width extends CF("width") with Length"""),
        // @media-only descriptors must NOT be emitted under ContainerFeatures.
        !src.contains("prefersReducedMotion"),
      )
    },
  )
end ContainerFeaturesGeneratorSpec
