package ascent.html

import ascent.ast.UI
import ascent.css.{CssClass, Declaration}
import zio.test.*

/** `renderPage` returns the HTML plus the CSS the rendered tree references. The catalog is per-render, so this needs no
  * global reset and no `@@ sequential` — and crucially, two renders never bleed CSS into each other (the SSR isolation
  * this change exists to guarantee: one web server serving different UIs).
  */
object HtmlPageSpec extends ZIOSpecDefault:

  private object Card extends CssClass(Declaration("padding", "8px"))
  private object Only extends CssClass(Declaration("color", "magenta"))

  def spec = suite("Html.renderPage")(
    test("collects the CSS of a CssClass applied in the tree") {
      val ui = UI.Element[Any]("div", Vector(Card.toAttr, Card.contribute), Vector(UI.Text("hi")))
      for page <- Html.renderPage(ui)
      yield assertTrue(
        page.html.contains(s"""class="${Card.className}""""),
        page.css.contains(s".${Card.className} {"),
        page.css.contains("padding: 8px;"),
      )
    },
    test("two independent renders emit DISJOINT CSS — no bleed between them") {
      val uiOnly = UI.Element[Any]("div", Vector(Only.contribute), Vector(UI.Text("x")))
      val uiCard = UI.Element[Any]("div", Vector(Card.contribute), Vector(UI.Text("y")))
      for
        onlyPage <- Html.renderPage(uiOnly)
        cardPage <- Html.renderPage(uiCard)
      yield assertTrue(
        onlyPage.css.contains(s".${Only.className} {"),
        !onlyPage.css.contains(s".${Card.className} {"), // Card never touched by this render
        cardPage.css.contains(s".${Card.className} {"),
        !cardPage.css.contains(s".${Only.className} {"), // the prior render's CSS did not leak in
      )
    },
  )
end HtmlPageSpec
