package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** CSS-in-Scala: Styles, CssClass, style sink. */
object Css extends DocSpec:

  object Card extends CssClass(
    S.padding.px(16),
    S.display.flex,
    Selector(":hover", S.color("cyan")),
  )

  def doc = page("CSS")(
    md"""
`ascent-css` is typed CSS-in-Scala: property objects, classes, and at-rules. Authoring is
platform-neutral; on the browser, `DomStyleSink` injects `<style>` into `<head>` as the tree
mounts. Class names are derived automatically from the Scala object.
""",
    section("Declarations and classes")(
      md"""
Use `S.padding.px(8)`, `S.display.flex`, and nested `Selector`s. Extend `CssClass` and pass the
object as an element arg; `E.div(Card, "hello")`.
""",
      example {
        E.div(Card, "hello")
      }.assert(ui => assertTrue(ui != null)),
      exampleValue {
        Card.className.nonEmpty
      }.assert(ok => assertTrue(ok)),
    ),
    section("Style sink")(
      md"""
Mount collects every `CssClass` / style attr into a per-render `StyleRegistry`. The JS entry
(`AscentApp.mount`) supplies `DomStyleSink`; SSR (`Html.renderPage`) snapshots CSS into a string
beside the markup. Two mounts never share a registry, but both dedup into the same `<head>` by
class key.
""",
      example {
        E.p("See ", E.a(A.href("html.html"), "HTML / SSR"), " for ", E.code("renderPage"), ".")
      }.assert(_ => assertTrue(true)),
    ),
  )
end Css
