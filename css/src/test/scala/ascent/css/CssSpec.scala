package ascent.css

import zio.*
import zio.test.*

/** The ascent-css value layer: how Declaration / Selector / MediaQuery compose into CSS, and how a `CssClass`
  * auto-generates a class name + injects its rules through a pluggable [[StyleSink]].
  */
object CssSpec extends ZIOSpecDefault:

  def spec = suite("ascent-css")(
    suite("Declaration")(
      test("renders as `name: value;`") {
        assertTrue(Declaration("color", "red").render == "color: red;")
      },
      test("two declarations render independently") {
        val ds = Vector(Declaration("color", "red"), Declaration("padding", "0"))
        assertTrue(ds.map(_.render) == Vector("color: red;", "padding: 0;"))
      },
    ),
    suite("Selector")(
      test("a selector with simple declarations renders as `<sel> { decls }`") {
        val s        = Selector(".foo", Declaration("color", "red"), Declaration("padding", "0"))
        val rendered = s.render
        assertTrue(
          rendered.startsWith(".foo {"),
          rendered.contains("color: red;"),
          rendered.contains("padding: 0;"),
          rendered.endsWith("}\n"),
        )
      },
      test("nested selectors prepend the parent selector to the child") {
        val nested = Selector(
          ".foo",
          Declaration("color", "red"),
          Selector(" .bar", Declaration("background", "blue")),
        )
        val rendered = nested.render
        assertTrue(
          rendered.contains(".foo {"),
          rendered.contains(".foo .bar {"),
          rendered.contains("color: red;"),
          rendered.contains("background: blue;"),
        )
      },
      test("a pseudo selector (`:hover`) chains without a separator") {
        val nested   = Selector(".foo", Selector(":hover", Declaration("color", "blue")))
        val rendered = nested.render
        assertTrue(rendered.contains(".foo:hover {"))
      },
    ),
    suite("MediaQuery")(
      test("a MediaQuery directly inside a Selector wraps the parent's nested rules in @media (q) { ... }") {
        val rule = Selector(
          ".foo",
          Declaration("color", "red"),
          MediaQuery(
            "(max-width: 600px)",
            Selector(":hover", Declaration("color", "blue")),
          ),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains(".foo {"),
          rendered.contains("color: red;"),
          // the @media wraps the full ancestor selector path, so the inner rule is `.foo:hover`
          rendered.contains("@media (max-width: 600px) {"),
          rendered.contains(".foo:hover {"),
          rendered.contains("color: blue;"),
          // regression: the at-rule must not concatenate onto the parent as `.foo@media`
          !rendered.contains(".foo@media"),
        )
      },
      test("declarations directly inside a MediaQuery are scoped to the parent selector") {
        val rule = Selector(
          ".foo",
          MediaQuery("(min-width: 800px)", Declaration("color", "red")),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains("@media (min-width: 800px) {"),
          rendered.contains(".foo {"),
          rendered.contains("color: red;"),
          // both the inner rule and the @media block must be closed
          rendered.count(_ == '}') >= 2,
        )
      },
      test("nested MediaQuery inside MediaQuery: both wrappers emitted, outermost first") {
        val rule = Selector(
          ".foo",
          MediaQuery(
            "(max-width: 800px)",
            MediaQuery("(prefers-reduced-motion: reduce)", Declaration("transition", "none")),
          ),
        )
        val rendered = rule.render
        val outerIdx = rendered.indexOf("@media (max-width: 800px)")
        val innerIdx = rendered.indexOf("@media (prefers-reduced-motion: reduce)")
        assertTrue(
          outerIdx >= 0,
          innerIdx > outerIdx,
          rendered.contains(".foo {"),
          rendered.contains("transition: none;"),
        )
      },
      test("MediaQuery at the top of a CssClass body wraps the class's name in the @media block") {
        object T
            extends CssClass(
              Declaration("position", "relative"),
              MediaQuery(
                "(prefers-reduced-motion: reduce)",
                Selector("::after", Declaration("transition", "opacity 0.1s ease")),
              ),
            )
        val css = T.renderCss
        assertTrue(
          css.contains(s".${T.className} {"),
          css.contains("@media (prefers-reduced-motion: reduce) {"),
          css.contains(s".${T.className}::after {"),
          css.contains("transition: opacity 0.1s ease;"),
          // regression: parent selector + at-rule literal must not concatenate
          !css.contains(s".${T.className}@media"),
        )
      },
    ),
    suite("StyleSink")(
      test("a CapturingSink records every style block written to it") {
        for
          sink  <- StyleSink.capturing
          _     <- sink.append("k1", ".k1 { color: red; }")
          _     <- sink.append("k2", ".k2 { color: blue; }")
          rules <- sink.captured
        yield assertTrue(
          rules.size == 2,
          rules.map(_._1) == Vector("k1", "k2"),
          rules.map(_._2) == Vector(".k1 { color: red; }", ".k2 { color: blue; }"),
        )
      },
      test("appending the same key twice replaces the previous block (idempotent re-injection)") {
        for
          sink  <- StyleSink.capturing
          _     <- sink.append("dup", "first")
          _     <- sink.append("dup", "second")
          rules <- sink.captured
        yield assertTrue(rules.size == 1, rules.head._2 == "second")
      },
      test("the noop sink discards everything (used by JVM/Native authoring without a DOM)") {
        for _ <- StyleSink.noop.append("k", "ignored")
        yield assertCompletes
      },
    ),
    suite("CssClass")(
      test("auto-generates a stable class name derived from the Scala class name") {
        object MyButton  extends CssClass(Declaration("color", "red"))
        object MyButton2 extends CssClass(Declaration("color", "red"))
        assertTrue(
          MyButton.className != MyButton2.className,
          MyButton.className == MyButton.className,
          MyButton.className.matches("[A-Za-z][A-Za-z0-9-]*"),
        )
      },
      test("toAttr produces a class attribute carrying the auto-generated class name") {
        object Toolbar extends CssClass(Declaration("display", "flex"))
        val attr = Toolbar.toAttr
        import ascent.ast.Attr
        import ascent.domtypes.AttrValue
        attr match
          case Attr.StaticAttr("class", AttrValue.Str(s)) =>
            assertTrue(s == Toolbar.className)
          case other => assertNever(s"expected StaticAttr(\"class\", ...), got $other")
      },
      test("instantiating with a StyleSink injects the rules under the class's auto-derived key") {
        object Hint extends CssClass(Declaration("color", "gray"), Declaration("font-size", "12px"))
        for
          sink  <- StyleSink.capturing
          _     <- Hint.installInto(sink)
          rules <- sink.captured
        yield assertTrue(rules.size == 1, rules.head._1 == Hint.className) &&
          assertTrue(rules.head._2.contains("color: gray;"), rules.head._2.contains("font-size: 12px;"))
      },
      test("the rendered CSS scopes every declaration under the auto-generated class") {
        object Card extends CssClass(Declaration("padding", "8px"))
        val css = Card.renderCss
        assertTrue(css.contains(s".${Card.className} {"), css.contains("padding: 8px;"))
      },
      test("a CssClass with a nested Selector emits the scoped variant rule") {
        object Toggle
            extends CssClass(
              Declaration("display", "block"),
              Selector(":hover", Declaration("color", "red")),
            )
        val css = Toggle.renderCss
        assertTrue(
          css.contains(s".${Toggle.className} {"),
          css.contains(s".${Toggle.className}:hover {"),
          css.contains("color: red;"),
        )
      },
    ),
    suite("GlobalRule")(
      test("Conversion[Selector, GlobalRule] keys off the selector string and carries its rendered CSS") {
        val sel: Selector = Selector(Elem.body, Declaration("margin", "0"))
        val rule          = summon[Conversion[Selector, GlobalRule]](sel)
        assertTrue(rule.key == sel.selector, rule.key == "body", rule.css == sel.render)
      },
      test("GlobalRule.selector(sel)(decls*) renders identical CSS to the conversion form") {
        val viaFactory = GlobalRule.selector(Elem.body)(Declaration("margin", "0"))
        val viaConv    = summon[Conversion[Selector, GlobalRule]](Selector(Elem.body, Declaration("margin", "0")))
        assertTrue(
          viaFactory.css == viaConv.css,
          viaFactory.key == viaConv.key,
          viaFactory.css.startsWith("body {"),
          viaFactory.css.contains("margin: 0;"),
        )
      },
      test("the universal selector lifts to a `* { ... }` rule") {
        val rule = GlobalRule.selector(Sel.universal)(Declaration("box-sizing", "border-box"))
        assertTrue(rule.key == "*", rule.css.startsWith("* {"), rule.css.contains("box-sizing: border-box;"))
      },
      test("GlobalRule.selector(key, sel)(decls*) keeps the explicit key, same CSS as the keyless form") {
        val keyed   = GlobalRule.selector("page-body", Elem.body)(Declaration("margin", "0"))
        val keyless = GlobalRule.selector(Elem.body)(Declaration("margin", "0"))
        assertTrue(keyed.key == "page-body", keyed.key != keyless.key, keyed.css == keyless.css)
      },
      test("Conversion[AtRuleBlock, GlobalRule] carries the block's rendered CSS") {
        val block: AtRuleBlock = MediaQuery(
          Media.prefersReducedMotion.reduce,
          Selector(Sel.universal, Declaration("animation-duration", "0.01ms")),
        )
        val rule = summon[Conversion[AtRuleBlock, GlobalRule]](block)
        assertTrue(rule.css == block.render, rule.css.contains("@media"))
      },
      test("GlobalRule.atRule(key, block) keeps the explicit key") {
        val block = SupportsQuery(
          Supports.declaration(Declaration("backdrop-filter", "blur(1px)")).not,
          Selector(".glass", Declaration("background", "black")),
        )
        val rule = GlobalRule.atRule("supports-no-backdrop", block)
        assertTrue(rule.key == "supports-no-backdrop", rule.css == block.render, rule.css.contains("@supports"))
      },
    ),
  )
end CssSpec
