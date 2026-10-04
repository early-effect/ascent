package ascent.html

import ascent.{A, E}
import ascent.ast.UI
import ascent.css.{CssClass, Declaration}
import ascent.dsl.*
import zio.*
import zio.test.*

/** Document SSR: doctype, the curried shell, attribute grouping, and CSS placement. `stripIds` drops the `data-ascent`
  * stamp so assertions pin structure, not a golden id.
  */
object HtmlDocumentSpec extends ZIOSpecDefault:

  private val stamp                          = """ data-ascent="[^"]*"""".r
  private def stripIds(html: String): String = stamp.replaceAllIn(html, "")

  private val viewport = "width=device-width, initial-scale=1"

  private object Box extends CssClass(Declaration("padding", "8px"))

  def spec = suite("Html.renderDocument")(
    suite("attribute grouping")(
      test("meta accepts name and content, and a fragment render keeps content") {
        val meta = E.meta(A.name("viewport"), A.content(viewport))
        for html <- Html.render(meta)
        yield assertTrue(stripIds(html).contains("content="))
      },
      test("div rejects httpEquiv; anchor accepts href; input rejects href; div accepts id and lang") {
        for
          divHttp   <- typeCheck("""ascent.E.div(ascent.A.httpEquiv("refresh"))""")
          anchor    <- typeCheck("""ascent.E.a(ascent.A.href("/x"))""")
          inputHref <- typeCheck("""ascent.E.input(ascent.A.href("/x"))""")
          divGlobal <- typeCheck("""ascent.E.div(ascent.A.id("x"), ascent.A.lang("en"))""")
        yield assertTrue(divHttp.isLeft, anchor.isRight, inputHref.isLeft, divGlobal.isRight)
      },
    ),
    suite("shell")(
      test("a host page is a document: doctype, charset, viewport, title, body script") {
        val ui = Document("Todos host")()(E.script(A.src("/host.js")))
        for
          page <- Html.renderDocument(ui, StylePlacement.Aside)
          frag <- Html.render(ui)
        yield
          val html       = stripIds(page.html)
          val charsetAt  = html.indexOf("""<meta charset="utf-8">""")
          val viewportAt = html.indexOf(s"""<meta name="viewport" content="$viewport">""")
          val titleAt    = html.indexOf("<title>Todos host</title>")
          val bodyAt     = html.indexOf("<body>")
          val scriptAt   = html.indexOf("""<script src="/host.js">""")
          assertTrue(
            html.startsWith("""<!DOCTYPE html><html lang="en">"""),
            charsetAt >= 0,
            viewportAt > charsetAt,
            titleAt > viewportAt,
            bodyAt > titleAt,
            scriptAt > bodyAt,
            !page.html.startsWith("<div"),
            !frag.contains("<!DOCTYPE"),
          )
        end for
      },
      test("a title containing < is escaped") {
        val ui = Document("a < b")()(E.p("x"))
        for page <- Html.renderDocument(ui, StylePlacement.Aside)
        yield assertTrue(stripIds(page.html).contains("<title>a &lt; b</title>"))
      },
      test("the head list stays in head and the body list stays in body") {
        val ui = Document("Todos host")(
          E.script(A.src("/analytics.js")),
          E.meta(A.name("description"), A.content("a host")),
        )(E.script(A.src("/host.js")))
        for page <- Html.renderDocument(ui, StylePlacement.Aside)
        yield
          val html      = stripIds(page.html)
          val headAt    = html.indexOf("<head>")
          val titleAt   = html.indexOf("<title>Todos host</title>")
          val analytics = html.indexOf("""src="/analytics.js"""")
          val desc      = html.indexOf("""content="a host"""")
          val bodyAt    = html.indexOf("<body>")
          val host      = html.indexOf("""src="/host.js"""")
          assertTrue(
            headAt >= 0,
            titleAt > headAt,
            analytics > titleAt,
            desc > titleAt,
            analytics < bodyAt,
            desc < bodyAt,
            host > bodyAt,
          )
        end for
      },
      test("lang overrides the html lang attribute") {
        val ui = Document("Todos host", lang = "fr")()(E.script(A.src("/host.js")))
        for page <- Html.renderDocument(ui, StylePlacement.Aside)
        yield assertTrue(stripIds(page.html).startsWith("""<!DOCTYPE html><html lang="fr">"""))
      },
      test("pretty indents head and body and is not the compact string") {
        val ui = Document("Todos host")()(E.script(A.src("/host.js")))
        for
          compact <- Html.renderDocument(ui, StylePlacement.Aside)
          pretty  <- Html.renderDocumentPretty(ui, StylePlacement.Aside)
        yield
          val html = stripIds(pretty.html)
          assertTrue(
            html.startsWith("<!DOCTYPE html>\n<html"),
            html.contains("\n  <head>"),
            html.contains("\n  <body>"),
            html.contains("""src="/host.js""""),
            stripIds(pretty.html) != stripIds(compact.html),
          )
        end for
      },
      test("Html.render of a div is a fragment with no doctype") {
        for html <- Html.render(E.div("hi"))
        yield assertTrue(!html.contains("<!DOCTYPE"), stripIds(html) == "<div>hi</div>")
      },
      test("an input value inside a document is reflected onto the value attribute") {
        val ui = Document("t")()(E.input(A.value("typed")))
        for page <- Html.renderDocument(ui, StylePlacement.Aside)
        yield assertTrue(stripIds(page.html).contains("""value="typed""""))
      },
    ),
    suite("errors")(
      test("rendering a body as a document fails with NotHtmlRoot") {
        for exit <- Html.renderDocument(E.body("x"), StylePlacement.Aside).exit
        yield assertTrue(exit == Exit.fail(Html.Error.NotHtmlRoot(List("body"))))
      },
      test("nothing mounted fails with NotHtmlRoot(Nil)") {
        for exit <- Html.renderDocument(UI.Empty, StylePlacement.Aside).exit
        yield assertTrue(exit == Exit.fail(Html.Error.NotHtmlRoot(Nil)))
      },
      test("InlineInHead with no head fails with NoHead") {
        for exit <- Html.renderDocument(E.html(E.body("x")), StylePlacement.InlineInHead).exit
        yield assertTrue(exit == Exit.fail(Html.Error.NoHead))
      },
    ),
    suite("css placement")(
      test("InlineInHead appends one style in head and still returns the CSS; Aside does neither injection") {
        val ui = Document("t")()(E.div(Box, "x"))
        for
          inline <- Html.renderDocument(ui, StylePlacement.InlineInHead)
          aside  <- Html.renderDocument(ui, StylePlacement.Aside)
        yield
          val inHead  = stripIds(inline.html)
          val styleAt = inHead.indexOf("<style>")
          val headEnd = inHead.indexOf("</head>")
          assertTrue(
            inline.css.contains("padding: 8px;"),
            inline.css == aside.css,
            styleAt >= 0,
            headEnd > styleAt,
            inHead.contains(inline.css),
            !inHead.contains("<style data-ascent"),
            !aside.html.contains("<style"),
          )
        end for
      }
    ),
  )
end HtmlDocumentSpec
