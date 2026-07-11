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
into a disposable in-memory DOM with the **same** `Mount` engine the browser uses, then reads
`innerHTML`. Server and client cannot drift.
""",
    section("render and renderPage")(
      exampleZIO {
        val ui = E.div(A.className("card"), E.h1("Hello"), E.p("rendered on the server"))
        Html.render(ui)
      }.assert(html => assertTrue(html.contains("Hello"), html.contains("card"))),
      exampleZIO {
        object Box extends CssClass(S.padding.px(8))
        Html.renderPage(E.div(Box, "styled"))
      }.assert(page => assertTrue(page.html.contains("styled"), page.css.nonEmpty)),
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
          n  <- sq(3)
          html <- Html.render(E.span(n.map(_.toString)))
        yield html
      }.assert(html => assertTrue(html.contains("3"))),
    ),
  )
end HtmlPage
