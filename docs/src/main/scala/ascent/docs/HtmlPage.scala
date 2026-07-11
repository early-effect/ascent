package ascent.docs

import ascent.*
import ascent.dsl.*
import ascent.html.Html
import specular.*
import zio.test.*

/** UI → HTML string SSR via the same Mount engine. */
object HtmlPage extends DocSpec:

  def doc = page("HTML / SSR")(
    md"""
`ascent-html` renders the same `UI` AST to a string. There is no separate server walker: it mounts
into a disposable in-memory DOM with the **same** `Mount` engine the browser uses, then serializes
the tree. Server and client cannot drift.

`Html.render` / `Html.renderPage` emit compact markup (what morph consumes). Docs below use
`renderPretty` / `renderPagePretty` so the result panel is readable; those are for humans only.
""",
    section("render and renderPage")(
      exampleZIO {
        val ui = E.div(A.className("card"), E.h1("Hello"), E.p("rendered on the server"))
        Html.renderPretty(ui)
      }.assert(html => assertTrue(html.contains("Hello"), html.contains("card"))),
      exampleZIO {
        object Box extends CssClass(S.padding.px(8))
        Html.renderPagePretty(E.div(Box, "styled"))
      }.withShow(p => s"${p.html}\n\n${p.css}")
        .assert(page => assertTrue(page.html.contains("styled"), page.css.nonEmpty)),
    ),
    section("Reactive snapshots")(
      md"""
Reactive boundaries are `.get`-ted for their current value and rendered inline; one static
snapshot, no observers. Event handlers and lifecycle hooks are omitted. `Scoped` runs once in a
fresh `Scope`, renders, and closes. `data-ascent` ids match the client `Mount` stamps when using
the same `IdMode`.
""",
      exampleZIO {
        for
          n    <- sq(3)
          html <- Html.renderPretty(E.span(n.map(_.toString)))
        yield html
      }.assert(html => assertTrue(html.contains("3"))),
    ),
  )
end HtmlPage
