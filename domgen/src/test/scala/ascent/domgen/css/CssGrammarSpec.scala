package ascent.domgen.css

import zio.test.*

/** [[CssGrammar]] AST + parser for the CSS value-grammar mini-language in webref's property `value:` field.
  *
  * Combinator precedence (loosest → tightest): `,` (only inside function args) < `|` (alternation) < `||` (any-order,
  * one-or-more) < `&&` (all-in-any-order) < juxtaposition (sequence) < multipliers / brackets / function calls.
  */
object CssGrammarSpec extends ZIOSpecDefault:

  import CssGrammar.*

  private def parse(s: String): Grammar = CssGrammar.parse(s) match
    case Right(g)  => g
    case Left(err) => throw new AssertionError(s"Parse failed for `$s`: $err")

  def spec = suite("CssGrammar parser")(
    suite("atoms")(
      test("a bare keyword") {
        assertTrue(parse("auto") == Keyword("auto"))
      },
      test("a hyphenated keyword stays intact") {
        assertTrue(parse("space-between") == Keyword("space-between"))
      },
      test("a type ref `<color>`") {
        assertTrue(parse("<color>") == TypeRef("color"))
      },
      test("a type ref with internal range bounds is captured verbatim (ranges stay informational)") {
        assertTrue(parse("<integer [0,∞]>") == TypeRef("integer [0,∞]"))
      },
      test("a property ref `<'background-color'>` (note inner quotes)") {
        assertTrue(parse("<'background-color'>") == PropertyRef("background-color"))
      },
    ),
    suite("multipliers")(
      test("`?` makes the operand optional") {
        assertTrue(parse("auto?") == Multiplied(Keyword("auto"), Multiplier.Optional))
      },
      test("`*` zero-or-more") {
        assertTrue(parse("<color>*") == Multiplied(TypeRef("color"), Multiplier.ZeroOrMore))
      },
      test("`+` one-or-more") {
        assertTrue(parse("<length>+") == Multiplied(TypeRef("length"), Multiplier.OneOrMore))
      },
      test("`#` is a CommaList with no range") {
        assertTrue(parse("<number>#") == Multiplied(TypeRef("number"), Multiplier.CommaList(None)))
      },
      test("`#{2,4}` is a CommaList with min/max") {
        assertTrue(
          parse("<number>#{2,4}") ==
            Multiplied(TypeRef("number"), Multiplier.CommaList(Some((2, Some(4)))))
        )
      },
      test("`{2}` is an exact-count Range(2,Some(2))") {
        assertTrue(parse("a{2}") == Multiplied(Keyword("a"), Multiplier.Range(2, Some(2))))
      },
      test("`{1,4}` is Range(1, Some(4))") {
        assertTrue(parse("a{1,4}") == Multiplied(Keyword("a"), Multiplier.Range(1, Some(4))))
      },
      test("`{2,}` is Range(2, None) — unbounded upper") {
        assertTrue(parse("a{2,}") == Multiplied(Keyword("a"), Multiplier.Range(2, None)))
      },
      test("`!` is the Required marker") {
        assertTrue(parse("a!") == Multiplied(Keyword("a"), Multiplier.Required))
      },
    ),
    suite("groups (square brackets)")(
      test("`[ a | b ]` produces a Group around an OneOf") {
        assertTrue(parse("[ a | b ]") == Group(OneOf(List(Keyword("a"), Keyword("b")))))
      },
      test("a multiplier on a group attaches to the Group, not to its inner alternation") {
        assertTrue(
          parse("[ <length> | <percentage> ]{1,4}") ==
            Multiplied(
              Group(OneOf(List(TypeRef("length"), TypeRef("percentage")))),
              Multiplier.Range(1, Some(4)),
            )
        )
      },
    ),
    suite("functions")(
      test("`rgb( <number>#{3} )` parses as a FunctionCall whose body is the inner grammar") {
        assertTrue(
          parse("rgb( <number>#{3} )") ==
            FunctionCall(
              "rgb",
              Multiplied(TypeRef("number"), Multiplier.CommaList(Some((3, Some(3))))),
            )
        )
      },
      test("a function whose body is itself an alternation") {
        val g = parse("rgb( <number> | <percentage> )")
        assertTrue(g == FunctionCall("rgb", OneOf(List(TypeRef("number"), TypeRef("percentage")))))
      },
    ),
    suite("alternation `|`")(
      test("two-way alternation") {
        assertTrue(parse("a | b") == OneOf(List(Keyword("a"), Keyword("b"))))
      },
      test("left-associative chain flattens to a flat list, not a nested binary tree") {
        assertTrue(parse("a | b | c") == OneOf(List(Keyword("a"), Keyword("b"), Keyword("c"))))
      },
      test("alternation between a type ref and a keyword") {
        assertTrue(
          parse("<length-percentage> | auto") ==
            OneOf(List(TypeRef("length-percentage"), Keyword("auto")))
        )
      },
    ),
    suite("any-order `||` and all-in-order `&&`")(
      test("`||` produces AnyOrderOneOrMore") {
        assertTrue(parse("a || b") == AnyOrderOneOrMore(List(Keyword("a"), Keyword("b"))))
      },
      test("`&&` produces AllInAnyOrder") {
        assertTrue(parse("a && b") == AllInAnyOrder(List(Keyword("a"), Keyword("b"))))
      },
      test("`||` binds tighter than `|`: `a | b || c` is `a | (b || c)`") {
        assertTrue(
          parse("a | b || c") ==
            OneOf(List(Keyword("a"), AnyOrderOneOrMore(List(Keyword("b"), Keyword("c")))))
        )
      },
      test("`&&` binds tighter than `||`: `a || b && c` is `a || (b && c)`") {
        assertTrue(
          parse("a || b && c") ==
            AnyOrderOneOrMore(List(Keyword("a"), AllInAnyOrder(List(Keyword("b"), Keyword("c")))))
        )
      },
    ),
    suite("juxtaposition (sequence)")(
      test("two adjacent atoms form a Sequence") {
        assertTrue(parse("first start") == Sequence(List(Keyword("first"), Keyword("start"))))
      },
      test("juxtaposition binds tighter than `&&`: `a b && c d` is `(a b) && (c d)`") {
        assertTrue(
          parse("a b && c d") ==
            AllInAnyOrder(
              List(
                Sequence(List(Keyword("a"), Keyword("b"))),
                Sequence(List(Keyword("c"), Keyword("d"))),
              )
            )
        )
      },
    ),
    suite("slash literal")(
      test("`a / b` becomes Sequence(a, SlashLiteral, b)") {
        // The slash is a literal token in shorthand grammars (`<line-width> / <line-style>`); surfacing it
        // lets codegen emit it or treat it as opaque structure.
        assertTrue(
          parse("a / b") ==
            Sequence(List(Keyword("a"), SlashLiteral, Keyword("b")))
        )
      }
    ),
    suite("real-world webref grammars")(
      test("`bolder | lighter | <font-weight-absolute>`") {
        assertTrue(
          parse("bolder | lighter | <font-weight-absolute>") ==
            OneOf(List(Keyword("bolder"), Keyword("lighter"), TypeRef("font-weight-absolute")))
        )
      },
      test("`<color> | transparent`") {
        assertTrue(
          parse("<color> | transparent") ==
            OneOf(List(TypeRef("color"), Keyword("transparent")))
        )
      },
      test("`auto | none | hover` (cursor-style alternation)") {
        assertTrue(
          parse("auto | none | hover") ==
            OneOf(List(Keyword("auto"), Keyword("none"), Keyword("hover")))
        )
      },
    ),
    suite("edge cases / negative")(
      test("an empty grammar returns Left (parser does not accept empty input)") {
        // Empty values appear in webref as `''` — informational descriptors, surfaced as a typed error.
        assertTrue(CssGrammar.parse("").isLeft, CssGrammar.parse("   ").isLeft)
      },
      test("a stray `]` returns Left (unbalanced bracket)") {
        assertTrue(CssGrammar.parse("a ]").isLeft)
      },
      test("a stray `}` is reported as a parse error, not silently consumed") {
        assertTrue(CssGrammar.parse("a }").isLeft)
      },
    ),
  )
end CssGrammarSpec
