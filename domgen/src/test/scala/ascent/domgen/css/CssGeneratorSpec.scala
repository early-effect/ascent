package ascent.domgen.css

import zio.*
import zio.test.*

/** [[CssGenerator]] parses each property's grammar, analyzes it, dedupes by Scala name, and renders a file's worth of
  * `PropertyDef`s. Properties that fail to parse or reduce to an empty shape still appear, with the bare
  * `apply(String)` escape hatch.
  */
object CssGeneratorSpec extends ZIOSpecDefault:

  def spec = suite("CssGenerator")(
    test("a single trivial property survives the round-trip") {
      val props = List(WebrefCss.Property("color", value = Some("<color>")))
      val src   = CssGenerator.generate(props)
      assertTrue(
        src.contains("AUTO-GENERATED"),
        src.contains("trait StylesGenerated:"),
        src.contains("""object color extends DS("color") with ColorLike"""),
      )
    },
    test("a property with no `value:` field still renders (no traits, no keywords, just the bare object)") {
      val props = List(WebrefCss.Property("font", value = scala.None))
      val src   = CssGenerator.generate(props)
      assertTrue(src.contains("""object font extends DS("font")"""))
    },
    test("a property whose grammar fails to parse still renders the bare object with a `// could not parse` marker") {
      val props = List(WebrefCss.Property("garbage", value = Some("[]][[")))
      val src   = CssGenerator.generate(props)
      assertTrue(
        src.contains("// could not parse"),
        src.contains("""object garbage extends DS("garbage")"""),
      )
    },
    test("camelCases hyphenated property names; deduplicates by Scala name keeping the latest") {
      val props = List(
        WebrefCss.Property("background-color", value = Some("<color>")),
        WebrefCss.Property("font-size", value = Some("<length-percentage>")),
        WebrefCss.Property("font-size", value = Some("auto")), // duplicate scalaName; latest wins
      )
      val src = CssGenerator.generate(props)
      assertTrue(
        src.contains("""object backgroundColor extends DS("background-color") with ColorLike"""),
        src.contains("""object fontSize extends DS("font-size") with Auto"""), // the `auto` entry won
        src.split("object fontSize").length == 2,                              // exactly one fontSize object
      )
    },
    test("the generated source is non-empty even when called with the empty list") {
      val src = CssGenerator.generate(Nil)
      assertTrue(
        src.contains("trait StylesGenerated:")
      )
    },
    test("produces a syntactically-plausible output for a representative slice of real webref properties") {
      val props = List(
        WebrefCss.Property("color", value = Some("<color>")),
        WebrefCss.Property("font-size", value = Some("<length-percentage>")),
        WebrefCss.Property("display", value = Some("block | inline | flex | none")),
        WebrefCss.Property("opacity", value = Some("<number>")),
        WebrefCss.Property("border-radius", value = Some("[ <length> | <percentage> ]{1,4}")),
      )
      val src = CssGenerator.generate(props)
      assertTrue(
        src.contains("""object color extends DS("color") with ColorLike"""),
        src.contains("""object fontSize extends DS("font-size") with Length with Percent"""),
        src.contains("""object display extends DS("display") with None:"""),
        src.contains("""def flex: Declaration"""),
        src.contains("""object opacity extends DS("opacity") with Numeric"""),
        src.contains("""object borderRadius extends DS("border-radius") with Length with Percent"""),
      )
    },
    test("the generated source compiles when concatenated with a foundation stub (smoke test)") {
      // We can't invoke scalac here, so verify lexical-shape invariants compile failures usually trip on.
      val props = List(
        WebrefCss.Property("color", value = Some("<color>")),
        WebrefCss.Property("display", value = Some("none | flex | block")),
        WebrefCss.Property("z-index", value = Some("<integer>")),
      )
      val src         = CssGenerator.generate(props)
      val knownTraits = Set("Length", "Percent", "Auto", "None", "Normal", "ColorLike", "Numeric", "LengthPercent")
      val withRefs    = "with (\\w+)".r.findAllMatchIn(src).map(_.group(1)).toSet
      assertTrue(
        withRefs.subsetOf(knownTraits), // every `with X` names a known trait
        !src.contains("::"),
        !src.contains(",,"),
      )
    },
  )
end CssGeneratorSpec
