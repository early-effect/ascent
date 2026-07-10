package ascent.domgen.css

import zio.test.*

/** [[PropertyAnalyzer]] turns a parsed CSS value [[CssGrammar]] into a [[PropertyShape]] (which value-grammar traits to
  * mix in, which keyword methods to emit).
  */
object PropertyAnalyzerSpec extends ZIOSpecDefault:

  private def analyze(grammar: String): PropertyShape =
    val ast = CssGrammar.parse(grammar) match
      case Right(g) => g
      case Left(e)  => throw new AssertionError(s"parse failed for `$grammar`: $e")
    PropertyAnalyzer.analyze(ast)

  def spec = suite("PropertyAnalyzer")(
    suite("trait inference from type-refs")(
      test("`<length>` produces Length, no Percent, no keywords") {
        val s = analyze("<length>")
        assertTrue(s.traits == List("Length"), s.keywords.isEmpty)
      },
      test("`<length-percentage>` produces both Length AND Percent") {
        val s = analyze("<length-percentage>")
        assertTrue(s.traits == List("Length", "Percent"), s.keywords.isEmpty)
      },
      test("`<percentage>` produces Percent only") {
        val s = analyze("<percentage>")
        assertTrue(s.traits == List("Percent"))
      },
      test("`<color>` produces ColorLike") {
        val s = analyze("<color>")
        assertTrue(s.traits == List("ColorLike"))
      },
      test("`<number>` and `<integer>` produce Numeric") {
        assertTrue(
          analyze("<number>").traits == List("Numeric"),
          analyze("<integer>").traits == List("Numeric"),
        )
      },
      test("an unknown type-ref produces no trait and no keyword") {
        val s = analyze("<font-weight-absolute>")
        assertTrue(s.traits.isEmpty, s.keywords.isEmpty)
      },
    ),
    suite("keyword handling")(
      test("`auto` becomes the Auto trait, NOT a method") {
        val s = analyze("auto")
        assertTrue(s.traits == List("Auto"), s.keywords.isEmpty)
      },
      test("`none` becomes the None trait") {
        val s = analyze("none")
        assertTrue(s.traits == List("None"))
      },
      test("`normal` becomes the Normal trait") {
        val s = analyze("normal")
        assertTrue(s.traits == List("Normal"))
      },
      test("any other bare keyword becomes a method (Scala-safe identifier)") {
        val s = analyze("bolder")
        assertTrue(s.traits.isEmpty, s.keywords == List(KeywordMethod("bolder", "bolder")))
      },
      test("a hyphenated keyword is camel-cased for the Scala name") {
        val s = analyze("space-between")
        assertTrue(s.keywords == List(KeywordMethod("spaceBetween", "space-between")))
      },
      test("a Scala-keyword keyword (`match`) keeps its dom name as the scalaName — the Renderer backticks it later") {
        val s = analyze("match")
        assertTrue(s.keywords == List(KeywordMethod("match", "match")))
      },
    ),
    suite("alternation: combine traits and keywords across branches")(
      test("`<length-percentage> | auto` (canonical trait order: Length, Percent, Auto, ...)") {
        val s = analyze("<length-percentage> | auto")
        assertTrue(
          s.traits == List("Length", "Percent", "Auto"),
          s.keywords.isEmpty,
        )
      },
      test("`<color> | transparent` (named-color extra)") {
        val s = analyze("<color> | transparent")
        assertTrue(
          s.traits == List("ColorLike"),
          s.keywords == List(KeywordMethod("transparent", "transparent")),
        )
      },
      test("`bolder | lighter | <font-weight-absolute>` (two methods, no trait)") {
        val s = analyze("bolder | lighter | <font-weight-absolute>")
        assertTrue(
          s.traits.isEmpty,
          s.keywords == List(
            KeywordMethod("bolder", "bolder"),
            KeywordMethod("lighter", "lighter"),
          ),
        )
      },
      test("multi-keyword alternation") {
        val s = analyze("flex-start | flex-end | center | baseline | stretch")
        assertTrue(
          s.keywords == List(
            KeywordMethod("flexStart", "flex-start"),
            KeywordMethod("flexEnd", "flex-end"),
            KeywordMethod("center", "center"),
            KeywordMethod("baseline", "baseline"),
            KeywordMethod("stretch", "stretch"),
          )
        )
      },
    ),
    suite("dedup + canonical ordering")(
      test("trait emitted at most once when the same type-ref appears multiple times") {
        val s = analyze("<length> | <length>")
        assertTrue(s.traits == List("Length"))
      },
      test("traits land in canonical order regardless of input ordering") {
        val a = analyze("auto | <color>")
        val b = analyze("<color> | auto")
        assertTrue(a.traits == b.traits, a.traits == List("Auto", "ColorLike"))
      },
      test("keywords stay in the order they appeared in the grammar (left-to-right)") {
        val s = analyze("c | a | b")
        assertTrue(s.keywords.map(_.scalaName) == List("c", "a", "b"))
      },
    ),
    suite("nested-grammar walk (depth into branch-like layers)")(
      test("a multiplied group (e.g. padding's `[ <length> | <percentage> ]{1,4}`) surfaces the inner alternatives") {
        val s = analyze("[ <length> | <percentage> ]{1,4}")
        assertTrue(s.traits == List("Length", "Percent"), s.keywords.isEmpty)
      },
      test("a sequence still surfaces its components' traits as alternatives (any member may be optional)") {
        val s = analyze("<length> <length>")
        assertTrue(s.traits == List("Length"), s.keywords.isEmpty)
      },
      test("a function call is opaque — its body belongs to the function, not to the property") {
        val s = analyze("rgb( <number>#{3} )")
        assertTrue(s.traits.isEmpty, s.keywords.isEmpty)
      },
    ),
    suite("real-world property grammars (sanity)")(
      test("font-weight: <font-weight-absolute> | bolder | lighter") {
        val s = analyze("<font-weight-absolute> | bolder | lighter")
        assertTrue(
          s.traits.isEmpty,
          s.keywords == List(
            KeywordMethod("bolder", "bolder"),
            KeywordMethod("lighter", "lighter"),
          ),
        )
      },
      test("display: a long keyword enumeration — `none` becomes a trait, everything else a method") {
        val s = analyze("block | inline | inline-block | flex | inline-flex | grid | none")
        assertTrue(
          s.traits == List("None"),
          s.keywords.map(_.scalaName) == List("block", "inline", "inlineBlock", "flex", "inlineFlex", "grid"),
        )
      },
    ),
  )
end PropertyAnalyzerSpec
