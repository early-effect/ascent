package ascent.css

import zio.test.*

/** Parser tests for [[SelectorGrammar]] — the CSS3 selector grammar parsed into the [[MatchNode]] AST that [[SelMatch]]
  * evaluates.
  */
object SelectorGrammarSpec extends ZIOSpecDefault:

  private def parses(s: String): Boolean = SelectorGrammar.parse(s).isRight

  def spec = suite("SelectorGrammar")(
    suite("simple selectors")(
      test("type, universal, class, id all parse") {
        assertTrue(parses("div"), parses("*"), parses(".card"), parses("#main"))
      },
      test("a compound with type + class + id + attribute + pseudo all together") {
        assertTrue(parses("""input.required#name[type="text"]:focus"""))
      },
      test("all six attribute operators parse") {
        assertTrue(
          parses("[required]"),
          parses("""[type="checkbox"]"""),
          parses("""[class~="foo"]"""),
          parses("""[lang|="en"]"""),
          parses("""[href^="https"]"""),
          parses("""[href$=".pdf"]"""),
          parses("""[href*="example"]"""),
        )
      },
      test("an unquoted attribute value parses too") {
        assertTrue(parses("[type=checkbox]"))
      },
    ),
    suite("combinators")(
      test("all five combinators parse, with or without surrounding whitespace") {
        assertTrue(
          parses(".a .b"),
          parses(".a > .b"),
          parses(".a>.b"),
          parses(".a + .b"),
          parses(".a+.b"),
          parses(".a ~ .b"),
          parses(".a~.b"),
          parses(".a || .b"),
        )
      },
      test("a 3-level descendant chain and mixed child+descendant parse") {
        assertTrue(parses("div .a .b"), parses("div > .a .b"), parses("div .a > .b"))
      },
    ),
    suite("selector lists")(
      test("a comma-separated list parses") {
        assertTrue(parses("div, .a, #b"))
      },
      test("a trailing comma with nothing after it is a parse error") {
        assertTrue(!parses("div,"))
      },
    ),
    suite("pseudo-classes")(
      test("structural pseudo-classes parse") {
        assertTrue(
          parses(":first-child"),
          parses(":last-child"),
          parses(":empty"),
          parses(":nth-child(2n+1)"),
          parses(":nth-child(odd)"),
        )
      },
      test("nth-child edge forms all parse") {
        assertTrue(
          parses(":nth-child(0)"),
          parses(":nth-child(-n+3)"),
          parses(":nth-child(n)"),
          parses(":nth-child(3n)"),
          parses(":nth-child(2n-1)"),
        )
      },
      test("deeply nested :not(:not(:not(.a))) parses") {
        assertTrue(parses(":not(:not(:not(.a)))"))
      },
      test("universal-with-attribute parses") {
        assertTrue(parses("*[href]"))
      },
      test("case-sensitivity is preserved verbatim — the grammar does no case-folding") {
        assertTrue(SelectorGrammar.parse("DIV") == SelectorGrammar.parse("DIV"))
      },
    ),
    suite("pathological cases")(
      test("an empty selector is a parse error") {
        assertTrue(!parses(""))
      },
      test("a selector that is only whitespace is a parse error") {
        assertTrue(!parses("   "))
      },
      test("an unclosed attribute bracket is a parse error") {
        assertTrue(!parses("[required"))
      },
      test("an unclosed pseudo-class paren is a parse error") {
        assertTrue(!parses(":nth-child(2n+1"))
      },
      test(":not() with an empty argument is a parse error") {
        assertTrue(!parses(":not()"))
      },
      test("a selector list where one branch is malformed fails the WHOLE parse, not partially") {
        assertTrue(!parses("div, $$$, span"))
      },
      test("a combinator with nothing before it is a parse error") {
        assertTrue(!parses("> div"))
      },
      test("a combinator with nothing after it is a parse error") {
        assertTrue(!parses("div >"))
      },
      test("adjacent combinators with nothing between them is a parse error") {
        assertTrue(!parses("div >> span"))
      },
    ),
  )
end SelectorGrammarSpec
