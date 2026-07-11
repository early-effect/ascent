package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Published modules: what to depend on vs internals. */
object Modules extends DocSpec:

  def doc = page("Modules")(
    md"""
ascent is a multi-module toolkit. Most apps need only a few artifacts; others are plumbing
published so consumers resolve transitively.
""",
    section("App-facing")(
      example {
        E.table(
          E.thead(E.tr(E.th("Artifact"), E.th("Use when"))),
          E.tbody(
            E.tr(E.td(E.code("ascent-core")), E.td("Always: Squawk, UI AST, DSL")),
            E.tr(E.td(E.code("ascent-js")), E.td("Browser mount / binding")),
            E.tr(E.td(E.code("ascent-css")), E.td("Typed CSS-in-Scala")),
            E.tr(E.td(E.code("ascent-conduit")), E.td("Optional conduit ", E.code("Ctx[M]"))),
            E.tr(E.td(E.code("ascent-html")), E.td("SSR string renderer")),
            E.tr(E.td(E.code("ascent-datastar")), E.td("Datastar protocol + SignalStore")),
            E.tr(E.td(E.code("ascent-datastar-js")), E.td("Browser datastar runtime")),
            E.tr(E.td(E.code("ascent-datastar-http")), E.td("zio-http datastar server bridge")),
          ),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Internals (transitive)")(
      md"""
`ascent-dom-types`, `ascent-dom-facade`, `ascent-dom-core`, and `ascent-mount-engine` are engine
internals. Depend on them only if you are extending the platform; ordinary apps get them
transitively.
""",
      example {
        E.ul(
          E.li(E.code("domgen"), " - JVM generator; never a runtime dep"),
          E.li(E.code("example/*"), " - Vite apps (todo-conduit, datastar-app, hybrid-chat)"),
        )
      }.assert(_ => assertTrue(true)),
    ),
  )
end Modules
