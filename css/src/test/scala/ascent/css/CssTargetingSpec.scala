package ascent.css

import ascent.ast.{AstId, UI}
import zio.test.*

/** `CssClass.targeting(node, members*)` — the value-form sibling of `CssScope`, targeting a single AST node's
  * structural id via `[data-ascent="<id>"]`-prefixed selectors.
  */
object CssTargetingSpec extends ZIOSpecDefault:

  def spec = suite("CssClass.targeting")(
    test("returns a CssScope that targets the node's data-ascent id") {
      val node     = UI.Element("h1", Vector.empty, Vector.empty)
      val expected = AstId.renderAttr(AstId.compute(node))
      val styles   = CssClass.targeting(node, Declaration("font-size", "24px"))
      assertTrue(
        styles.rootSelector == s"""[data-ascent="$expected"]""",
        styles.renderCss.contains(s"""[data-ascent="$expected"] {"""),
        styles.renderCss.contains("font-size: 24px;"),
      )
    },
    test("supports nested Selectors just like an `object extends CssScope`") {
      val node     = UI.Element("section", Vector.empty, Vector.empty)
      val expected = AstId.renderAttr(AstId.compute(node))
      val styles   = CssClass.targeting(
        node,
        Declaration("padding", "8px"),
        Selector(" h1", Declaration("color", "red")),
      )
      val css = styles.renderCss
      assertTrue(
        css.contains(s"""[data-ascent="$expected"] {"""),
        css.contains("padding: 8px;"),
        css.contains(s"""[data-ascent="$expected"] h1 {"""),
        css.contains("color: red;"),
      )
    },
    test("two different AST nodes get different data-ascent prefixes") {
      val a  = UI.Element("div", Vector.empty, Vector.empty)
      val b  = UI.Element("section", Vector.empty, Vector.empty)
      val sa = CssClass.targeting(a, Declaration("color", "red"))
      val sb = CssClass.targeting(b, Declaration("color", "blue"))
      assertTrue(
        sa.rootSelector != sb.rootSelector,
        !sa.renderCss.contains(AstId.renderAttr(AstId.compute(b))),
      )
    },
  )
end CssTargetingSpec
