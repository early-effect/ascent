package ascent.domgen.css

import zio.test.*

/** [[CssRenderer]] turns analyzed property defs into the body of the generated `StylesGenerated.scala` file. */
object CssRendererSpec extends ZIOSpecDefault:

  private def render(name: String, shape: PropertyShape): String =
    CssRenderer.renderProperty(PropertyDef(scalaName = camel(name), domName = name, shape = shape))

  private def camel(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString

  def spec = suite("CssRenderer")(
    suite("a single property")(
      test("a property with traits but no keyword methods extends the traits and has no body") {
        val src = render("padding", PropertyShape(List("Length", "Percent"), Nil))
        assertTrue(
          src.contains("""object padding extends DS("padding")"""),
          src.contains("with Length"),
          src.contains("with Percent"),
        )
      },
      test("a property with both traits and keywords gets a `:`-block body listing the keyword defs") {
        val src = render(
          "font-style",
          PropertyShape(
            traits = List("Normal"),
            keywords = List(KeywordMethod("italic", "italic"), KeywordMethod("oblique", "oblique")),
          ),
        )
        assertTrue(
          src.contains("""object fontStyle extends DS("font-style") with Normal:"""),
          src.contains("""def italic: Declaration  = apply("italic")"""),
          src.contains("""def oblique: Declaration = apply("oblique")"""),
        )
      },
      test("a hyphenated dom name camel-cases the Scala name but keeps the dom string") {
        val src = render(
          "flex-direction",
          PropertyShape(
            traits = Nil,
            keywords = List(
              KeywordMethod("row", "row"),
              KeywordMethod("rowReverse", "row-reverse"),
            ),
          ),
        )
        assertTrue(
          src.contains("""object flexDirection extends DS("flex-direction"):"""),
          src.contains("""def rowReverse: Declaration = apply("row-reverse")"""),
        )
      },
      test("a property whose Scala name would clash with a Scala keyword is backticked") {
        val src = render("type", PropertyShape(Nil, Nil))
        assertTrue(src.contains("""object `type` extends DS("type")"""))
      },
      test("a method whose Scala name clashes with a Scala keyword (`super`, `match`) is backticked") {
        val src = render(
          "vertical-align",
          PropertyShape(
            traits = Nil,
            keywords = List(
              KeywordMethod("super", "super"),
              KeywordMethod("sub", "sub"),
            ),
          ),
        )
        assertTrue(
          src.contains("""def `super`: Declaration"""),
          src.contains("""def sub: Declaration"""),
        )
      },
      test("a property with no traits and no keywords still produces a usable empty object") {
        val src = render("transition-property", PropertyShape(Nil, Nil))
        assertTrue(
          src.contains("""object transitionProperty extends DS("transition-property")"""),
          !src.contains("with "),
        )
      },
    ),
    suite("file-level: trait wrapping + header")(
      test("renderTrait emits a header, the package, the foundation import, and a `trait StylesGenerated` shell") {
        val src = CssRenderer.renderTrait(
          List(
            PropertyDef("color", "color", PropertyShape(List("ColorLike"), Nil)),
            PropertyDef("display", "display", PropertyShape(List("None"), List(KeywordMethod("flex", "flex")))),
          )
        )
        assertTrue(
          src.contains("AUTO-GENERATED"),
          src.contains("package ascent.css"),
          src.contains("import StylesFoundation.*"),
          src.contains("trait StylesGenerated:"),
          src.contains("""object color extends DS("color") with ColorLike"""),
          src.contains("""object display extends DS("display") with None:"""),
          src.contains("""def flex: Declaration"""),
        )
      },
      test("two properties produce two distinct objects, in input order") {
        val src = CssRenderer.renderTrait(
          List(
            PropertyDef("first", "first", PropertyShape(Nil, Nil)),
            PropertyDef("second", "second", PropertyShape(Nil, Nil)),
          )
        )
        val firstIdx  = src.indexOf("object first")
        val secondIdx = src.indexOf("object second")
        assertTrue(firstIdx > 0, secondIdx > 0, firstIdx < secondIdx)
      },
    ),
  )
end CssRendererSpec
