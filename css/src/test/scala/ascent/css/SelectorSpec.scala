package ascent.css

import zio.test.*

/** The typed selector surface: the [[Sel]] fragment ADT, all five combinators, the [[PseudoClass]] / [[PseudoElement]]
  * catalogs, the `An+B` [[Nth]] helper, and [[Selector]] integration. The core contract: a `Sel` renders with NO
  * leading combinator, so nesting prepends the parent and selector-lists distribute the parent across comma segments.
  */
object SelectorSpec extends ZIOSpecDefault:

  object Card extends CssClass(Declaration("color", "red"))

  def spec = suite("Selector (typed)")(
    suite("Sel fragments")(
      test("class / id / type / universal / attribute") {
        assertTrue(
          Cls("row").rendered == ".row",
          Id("main").rendered == "#main",
          Elem("li").rendered == "li",
          Sel.universal.rendered == "*",
          Sel.attr("required").rendered == "[required]",
          Sel.attr("type", AttrOp.Eq, "checkbox").rendered == """[type="checkbox"]""",
          Sel.attr("href", AttrOp.Prefix, "https").rendered == """[href^="https"]""",
        )
      },
      test("Cls(CssClass) reads the derived class name") {
        assertTrue(Cls(Card).rendered == s".${Card.className}")
      },
      test("fluent compound chaining: cls().id().pseudoClass()") {
        assertTrue(
          Cls("a").id("main").pseudoClass(PseudoClass.hover).rendered == ".a#main:hover",
          Elem("li").cls(Card).rendered == s"li.${Card.className}",
          Elem("input").attr("required").pseudoClass(PseudoClass.checked).rendered == "input[required]:checked",
        )
      },
      test("Sel.raw escape hatch passes the string through verbatim") {
        assertTrue(Sel.raw(":nth-child(odd) > .x").rendered == ":nth-child(odd) > .x")
      },
      test("generated typed element tags (Elem.button etc.) + keyword-named elements") {
        assertTrue(
          Elem.button.rendered == "button",
          Elem.div.rendered == "div",
          Elem.li.rendered == "li",
          Elem.input.rendered == "input",
          Elem.`object`.rendered == "object",
          Elem.`var`.rendered == "var",
          Elem("my-widget").rendered == "my-widget",
        )
      },
    ),
    suite("combinators (all five)")(
      test("binary forms render the spec separators") {
        val a = Cls("a")
        val b = Cls("b")
        assertTrue(
          a.descendant(b).rendered == ".a .b",
          a.child(b).rendered == ".a > .b",
          a.nextSibling(b).rendered == ".a + .b",
          a.subsequentSibling(b).rendered == ".a ~ .b",
          a.column(b).rendered == ".a || .b",
          a.or(b).rendered == ".a, .b",
        )
      },
      test("relative (parent-implicit) forms carry a leading combinator") {
        assertTrue(
          Sel.descendant(Elem("canvas")).rendered == " canvas",
          Sel.child(Cls("x")).rendered == " > .x",
          Sel.nextSibling(Cls("x")).rendered == " + .x",
        )
      },
    ),
    suite("pseudo-classes")(
      test("simple generated pseudo-classes render with one colon") {
        assertTrue(
          PseudoClass.hover.render == ":hover",
          PseudoClass.focusVisible.render == ":focus-visible",
          PseudoClass.firstChild.render == ":first-child",
          PseudoClass.checked.render == ":checked",
        )
      },
      test("functional nth-* with the Nth helper") {
        assertTrue(
          PseudoClass.nthChild(Nth.odd).render == ":nth-child(odd)",
          PseudoClass.nthChild(Nth.even).render == ":nth-child(even)",
          PseudoClass.nthChild(Nth(3)).render == ":nth-child(3)",
          PseudoClass.nthChild(Nth(2, 1)).render == ":nth-child(2n+1)",
          PseudoClass.nthLastChild(Nth(-1, 3)).render == ":nth-last-child(-n+3)",
          PseudoClass.nthOfType(Nth(3, 0)).render == ":nth-of-type(3n)",
        )
      },
      test("selector-list functionals (not/is/where/has)") {
        assertTrue(
          PseudoClass.not(Cls("done"), Cls("hidden")).render == ":not(.done, .hidden)",
          PseudoClass.is(Elem("h1"), Elem("h2")).render == ":is(h1, h2)",
          PseudoClass.where(Cls("x")).render == ":where(.x)",
          PseudoClass.has(Sel.child(Cls("y"))).render == ":has( > .y)",
        )
      },
      test("token functionals (dir/lang/state)") {
        assertTrue(
          PseudoClass.dir("rtl").render == ":dir(rtl)",
          PseudoClass.lang("zh-Hans").render == ":lang(zh-Hans)",
          PseudoClass.state("checked").render == ":state(checked)",
        )
      },
    ),
    suite("pseudo-elements")(
      test("simple generated pseudo-elements render with two colons") {
        assertTrue(
          PseudoElement.before.render == "::before",
          PseudoElement.after.render == "::after",
          PseudoElement.selection.render == "::selection",
          PseudoElement.marker.render == "::marker",
        )
      },
      test("legacy single-colon pseudo-element aliases are NOT emitted (only the :: spelling)") {
        // webref lists both `:before` and `::before`; the generator drops the legacy single-colon spelling.
        assertTrue(!PseudoElement.before.render.matches(":[a-z].*"))
      },
      test("functional pseudo-elements (highlight/part/slotted)") {
        assertTrue(
          PseudoElement.highlight("search").render == "::highlight(search)",
          PseudoElement.part("label", "icon").render == "::part(label icon)",
          PseudoElement.slotted(Cls("x")).render == "::slotted(.x)",
        )
      },
    ),
    suite("Nth render")(
      test("An+B normalization") {
        assertTrue(
          Nth(0, 5).render == "5",
          Nth(1, 0).render == "n",
          Nth(-1, 3).render == "-n+3",
          Nth(2, -1).render == "2n-1",
          Nth(3, 0).render == "3n",
          Nth.odd.render == "odd",
          Nth.even.render == "even",
        )
      }
    ),
    suite("Selector integration")(
      test("Selector(Sel, members*) renders a rule") {
        assertTrue(
          Selector(Cls("a").pseudoClass(PseudoClass.hover), Declaration("color", "red")).render
            == ".a:hover {\n  color: red;\n}\n"
        )
      },
      test("composed Selector(parts*)(members*) compounds the parts") {
        val css = Selector(Cls(Card), PseudoClass.hover)(Declaration("color", "red")).render
        assertTrue(css == s".${Card.className}:hover {\n  color: red;\n}\n")
      },
      test("nested typed selector prepends the parent (relative descendant)") {
        val css = Selector(
          Cls("toggle-all"),
          Declaration("display", "flex"),
          Selector(Sel.descendant(Elem("canvas")), Declaration("vertical-align", "middle")),
        ).render
        assertTrue(
          css.contains(".toggle-all {\n  display: flex;\n}\n"),
          css.contains(".toggle-all canvas {\n  vertical-align: middle;\n}\n"),
        )
      },
      test("nested pseudo-class attaches with no separator") {
        val css = Selector(
          Cls("btn"),
          Selector(PseudoClass.hover, Declaration("color", "cyan")),
        ).render
        assertTrue(css.contains(".btn:hover {\n  color: cyan;\n}\n"))
      },
      test("selector-list distributes the parent across comma segments when nested") {
        val css = Selector(
          Cls("btn"),
          // Each segment carries its own attaching combinator, so the parent prepends to every segment:
          // `.btn:hover, .btn:focus` — NOT the invalid `.btn:hover, :focus`.
          Selector(PseudoClass.hover.or(PseudoClass.focus), Declaration("cursor", "pointer")),
        ).render
        assertTrue(css.contains(".btn:hover, .btn:focus {\n  cursor: pointer;\n}\n"))
      },
      test("Selector.cls / .pseudoClass / .tag factories") {
        assertTrue(
          Selector.cls(Card)(Declaration("color", "red")).render
            == s".${Card.className} {\n  color: red;\n}\n",
          Selector.pseudoClass(PseudoClass.focusVisible)(Declaration("outline", "none")).render
            == ":focus-visible {\n  outline: none;\n}\n",
        )
      },
    ),
  )
end SelectorSpec
