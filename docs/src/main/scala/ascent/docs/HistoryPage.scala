package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** Optional URL session: Location as a Squawk, memory and browser backends. */
object HistoryPage extends DocSpec:

  def doc = page("History")(
    md"""
`ascent-history` is an optional URL session bound to Squawk. The current location (path, query, hash)
is a value-over-time; `push` / `replace` are effects; Back writes the Squawk. The already-mounted
tree patches at reactive boundaries. This is **not a router**: no path matcher, nested layouts, or
data loaders. Matching is `hist.location.map(parse)` plus `when`. A later `ascent.router` may sit
on this primitive; it must not remount `AscentApp`.
""",
    section("Memory backend")(
      md"""
`History.memory` is the backend for tests, Chekhov / JSEnv, VS Code webviews, and SSR (seed from
the request URL). `push` of the current URL still records a stack entry; `Eq[Location]` is what
stops observers firing.
""",
      exampleZIO {
        for
          hist <- History.memory()
          _    <- hist.push("/a")
          _    <- hist.push("/b")
          _    <- hist.back
          here <- hist.location.get
        yield here.href
      }.assert(s => assertTrue(s == "/a")),
    ),
    section("Derive an ADT")(
      md"""
Hash-mode apps can use a real `<a href="#/active">` and let `hashchange` update the Squawk.
Path-mode apps call `hist.push("/session/id")` from handlers. There is no window-level click
capture (a later `Link` helper would call History; it must not hijack every `<a>`).
""",
      exampleIO {
        for hist <- History.memory()
        yield
          val here = hist.location.map(_.href)
          E.div(
            E.span(here),
            E.button(Events.onClick(_ => hist.push("/next")), "go"),
          )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("Browser backend")(
      md"""
On Scala.js, `History.browser` binds `window.history` plus `popstate` / `hashchange`. Construction
is `URIO[Scope, History]`; Scope close removes the listeners. `ZIOAppDefault.run` already has a
Scope.
"""
    ),
  )
end HistoryPage
