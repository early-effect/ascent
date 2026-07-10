package ascent.domgen.css

import zio.*
import zio.test.*

import scala.io.Source

/** Webref CSS JSON parser: ingests `data/webref/css/<spec>.json` files into one normalized [[WebrefCss.Catalog]]. Most
  * tests use small inline fixtures; a handful sanity-check the real snapshot.
  */
object WebrefCssSpec extends ZIOSpecDefault:

  private def realSpec(name: String): String =
    val src = Source.fromFile(s"data/webref/css/$name", "UTF-8")
    try src.mkString
    finally src.close()

  def spec = suite("WebrefCss JSON parser")(
    suite("property")(
      test("parses name + value (the minimum a property must have)") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [
            |  { "name": "color", "value": "<color>" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield assertTrue(
          s.properties.size == 1,
          s.properties.head.name == "color",
          s.properties.head.value == Some("<color>"),
        )
      },
      test("a property without a `value:` field has value=None (some shorthands omit it)") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [
            |  { "name": "font" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield assertTrue(s.properties.head.name == "font", s.properties.head.value == None)
      },
      test("captures the optional metadata fields when present") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [
            |  { "name": "font-weight", "value": "<font-weight-absolute> | bolder | lighter",
            |    "initial": "normal", "inherited": "yes" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield
          val p = s.properties.head
          assertTrue(
            p.value == Some("<font-weight-absolute> | bolder | lighter"),
            p.initial == Some("normal"),
            p.inherited == Some("yes"),
          )
      },
    ),
    suite("prose (the scaladoc source)")(
      test("a value child's prose is captured for scaladoc generation (only children carry prose, not the property)") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [
            |  { "name": "font-style", "value": "normal | italic",
            |    "values": [
            |      { "name": "italic", "type": "value", "value": "italic",
            |        "prose": "Matches against a font labeled as an italic face." }
            |    ] }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield
          val italic = s.properties.head.values.find(_.name == "italic").get
          assertTrue(italic.prose == Some("Matches against a font labeled as an italic face."))
      },
      test("absent prose is None (most type defs have none)") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [], "atrules": [], "selectors": [],
            |  "values": [ { "name": "auto", "type": "value", "value": "auto" } ] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield assertTrue(s.values.head.prose == None)
      },
      test("real snapshot: font-style's `italic` keyword carries prose") {
        for s <- WebrefCss.parseSpec(realSpec("css-fonts.json"))
        yield
          val fontStyle = s.properties.find(_.name == "font-style").get
          val italic    = fontStyle.values.find(_.name == "italic").get
          assertTrue(italic.prose.exists(_.contains("italic")))
      },
    ),
    suite("atrule")(
      test("parses atrule descriptors as property-shaped entries") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [],
            |  "atrules": [ { "name": "@font-face", "descriptors": [
            |    { "name": "font-family", "value": "<font-family-name>", "initial": "N/A" }
            |  ] } ],
            |  "selectors": [], "values": [] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield
          val r = s.atrules.head
          assertTrue(
            r.name == "@font-face",
            r.descriptors.size == 1,
            r.descriptors.head.name == "font-family",
            r.descriptors.head.value == Some("<font-family-name>"),
          )
      }
    ),
    suite("values (type / value definitions)")(
      test("a top-level value with no nested children") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [], "atrules": [], "selectors": [],
            |  "values": [ { "name": "<color>", "type": "type", "value": "<color-base> | currentColor" } ] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield
          val v = s.values.head
          assertTrue(
            v.name == "<color>",
            v.kind == "type",
            v.value == Some("<color-base> | currentColor"),
            v.children.isEmpty,
          )
      },
      test("a value whose `values:` field nests further definitions (recursive)") {
        val json =
          """{ "spec": { "title": "x", "url": "u" }, "properties": [], "atrules": [], "selectors": [],
            |  "values": [
            |    { "name": "<color>", "type": "type", "value": "<color-base>",
            |      "values": [
            |        { "name": "<alpha-value>", "type": "type", "value": "<number> | <percentage>" },
            |        { "name": "transparent", "type": "value", "value": "transparent" }
            |      ] }
            |  ] }""".stripMargin
        for s <- WebrefCss.parseSpec(json)
        yield
          val v = s.values.head
          assertTrue(
            v.children.size == 2,
            v.children.head.name == "<alpha-value>",
            v.children.head.kind == "type",
            v.children(1).name == "transparent",
            v.children(1).kind == "value",
          )
        end for
      },
    ),
    suite("Catalog (multi-spec aggregation)")(
      test("merges all properties / atrules / values across multiple parsed specs") {
        val a =
          """{ "spec": { "title": "A", "url": "u" }, "properties": [
            |  { "name": "color", "value": "<color>" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        val b =
          """{ "spec": { "title": "B", "url": "u" }, "properties": [
            |  { "name": "background-color", "value": "<color>" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for
          sa <- WebrefCss.parseSpec(a)
          sb <- WebrefCss.parseSpec(b)
          cat = WebrefCss.Catalog.fromSpecs(List(sa, sb))
        yield assertTrue(
          cat.properties.map(_.name).sorted == List("background-color", "color")
        )
      },
      test("when two specs define the same property WITH a value, the LAST wins (later vendoring overrides)") {
        val older =
          """{ "spec": { "title": "old", "url": "u" }, "properties": [
            |  { "name": "color", "value": "<old-color>" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        val newer =
          """{ "spec": { "title": "new", "url": "u" }, "properties": [
            |  { "name": "color", "value": "<color>" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for
          o <- WebrefCss.parseSpec(older)
          n <- WebrefCss.parseSpec(newer)
          cat = WebrefCss.Catalog.fromSpecs(List(o, n))
        yield assertTrue(
          cat.properties.size == 1,
          cat.properties.head.value == Some("<color>"),
        )
      },
      test("a later spec WITHOUT a `value:` field does NOT clobber an earlier one with the canonical grammar") {
        // e.g. `display` appears in css-display.json with a grammar AND in mathml-core.json with no value.
        val canonical =
          """{ "spec": { "title": "canonical", "url": "u" }, "properties": [
            |  { "name": "display", "value": "[ <display-outside> ]" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        val partial =
          """{ "spec": { "title": "partial", "url": "u" }, "properties": [
            |  { "name": "display" }
            |], "atrules": [], "selectors": [], "values": [] }""".stripMargin
        for
          c <- WebrefCss.parseSpec(canonical)
          p <- WebrefCss.parseSpec(partial)
          cat = WebrefCss.Catalog.fromSpecs(List(c, p))
        yield assertTrue(
          cat.properties.size == 1,
          cat.properties.head.value == Some("[ <display-outside> ]"),
        )
      },
    ),
    suite("real vendored snapshot (sanity)")(
      test("css-color.json parses; the `color` property has value `<color>`") {
        for s <- WebrefCss.parseSpec(realSpec("css-color.json"))
        yield
          val color = s.properties.find(_.name == "color").get
          assertTrue(color.value == Some("<color>"))
      },
      test("css-fonts.json parses and exposes the @font-face atrule with a font-family descriptor") {
        for s <- WebrefCss.parseSpec(realSpec("css-fonts.json"))
        yield
          val ff = s.atrules.find(_.name == "@font-face").get
          assertTrue(ff.descriptors.exists(_.name == "font-family"))
      },
      test("EVERY vendored CSS spec parses without crashing — top-line robustness gate") {
        // On any Left, fail with the file name + error, so an unhandled webref shape is named.
        import java.nio.file.*
        import scala.jdk.CollectionConverters.*
        ZIO
          .attemptBlocking {
            val dir   = Paths.get("data/webref/css")
            val files = Files
              .list(dir)
              .iterator()
              .asScala
              .toList
              .filter(_.toString.endsWith(".json"))
              .sortBy(_.getFileName.toString)
            files.map(p => p.getFileName.toString -> Files.readString(p))
          }
          .flatMap { files =>
            ZIO.foreach(files) { case (name, text) =>
              WebrefCss.parseSpec(text).either.map(name -> _)
            }
          }
          .map { results =>
            val failures = results.collect { case (name, Left(err)) => name -> err }
            assertTrue(failures == Nil)
          }
          .orDie
      },
    ),
  )
end WebrefCssSpec
