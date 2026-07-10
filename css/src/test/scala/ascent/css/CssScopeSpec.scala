package ascent.css

import ascent.ast.{AstId, UI}
import zio.test.*

/** Component-scoped CSS keyed to a UI subtree's structural id: every rule is prefixed with `[data-ascent="<rootId>"]`.
  */
object CssScopeSpec extends ZIOSpecDefault:

  def spec = suite("CssScope")(
    test("renders rules prefixed with [data-ascent=<rootId>]") {
      val root     = UI.Element("div", Vector.empty, Vector.empty)
      val rootAttr = AstId.renderAttr(AstId.compute(root))
      object scope extends CssScope(root, Declaration("color", "red"))
      val css = scope.renderCss
      assertTrue(
        css.contains(s"""[data-ascent="$rootAttr"] {"""),
        css.contains("color: red;"),
      )
    },
    test("nested selectors descend into the scope's root") {
      val root     = UI.Element("section", Vector.empty, Vector.empty)
      val rootAttr = AstId.renderAttr(AstId.compute(root))
      object scope
          extends CssScope(
            root,
            Declaration("padding", "8px"),
            Selector(" h1", Declaration("font-size", "24px")),
            Selector(" .item", Declaration("color", "blue")),
          )
      val css = scope.renderCss
      assertTrue(
        css.contains(s"""[data-ascent="$rootAttr"] {"""),
        css.contains("padding: 8px;"),
        css.contains(s"""[data-ascent="$rootAttr"] h1 {"""),
        css.contains("font-size: 24px;"),
        css.contains(s"""[data-ascent="$rootAttr"] .item {"""),
        css.contains("color: blue;"),
      )
    },
    test("two scopes for two distinct AST roots get DIFFERENT data-ascent prefixes") {
      val rootA = UI.Element("div", Vector.empty, Vector.empty)
      val rootB = UI.Element("section", Vector.empty, Vector.empty)
      object scopeA extends CssScope(rootA, Declaration("color", "red"))
      object scopeB extends CssScope(rootB, Declaration("color", "blue"))
      val attrA = AstId.renderAttr(AstId.compute(rootA))
      val attrB = AstId.renderAttr(AstId.compute(rootB))
      assertTrue(
        attrA != attrB,
        scopeA.renderCss.contains(s"""[data-ascent="$attrA"]"""),
        scopeB.renderCss.contains(s"""[data-ascent="$attrB"]"""),
        !scopeA.renderCss.contains(attrB),
        !scopeB.renderCss.contains(attrA),
      )
    },
    test("installInto registers the scope's CSS under a key derived from the root id") {
      val root = UI.Element("article", Vector.empty, Vector.empty)
      object scope extends CssScope(root, Declaration("background", "yellow"))
      for
        sink  <- StyleSink.capturing
        _     <- scope.installInto(sink)
        rules <- sink.captured
      yield assertTrue(
        rules.size == 1,
        rules.head._1 == s"scope-${AstId.renderAttr(AstId.compute(root))}",
        rules.head._2.contains("background: yellow;"),
      )
    },
    test("installing the same scope twice replaces the previous rule (idempotent)") {
      val root = UI.Element("article", Vector.empty, Vector.empty)
      object scope extends CssScope(root, Declaration("background", "yellow"))
      for
        sink  <- StyleSink.capturing
        _     <- scope.installInto(sink)
        _     <- scope.installInto(sink)
        rules <- sink.captured
      yield assertTrue(rules.size == 1)
    },
  )
end CssScopeSpec
