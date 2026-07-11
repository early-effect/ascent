package ascent.docs

import ascent.*
import ascent.dsl.*
import ascent.datastar.{SignalPatch, SignalStore}
import specular.*
import zio.json.ast.Json
import zio.test.*

/** DOM-free datastar protocol: SignalStore and patches. */
object DatastarPage extends DocSpec:

  def doc = page("Datastar")(
    md"""
`ascent-datastar` is the **protocol** core; DOM-free and platform-neutral. Incoming
`patch-signals` map onto ascent's reactive primitive: a typed signal delta becomes `Source.set`,
and fine-grained boundaries repaint. Focus and caret stay put; no idiomorph required.
""",
    section("SignalStore")(
      md"""
Declare each signal with `store.squawk(name, init)` (or `store.source` for writable two-way
bindings). Patches for unknown names are logged no-ops; decode failures retain the prior value.
""",
      exampleZIO {
        for
          store <- SignalStore.make()
          count <- store.squawk("count", 0)
          _     <- store.route(SignalPatch.Put("count", Json.Num(7), onlyIfMissing = false))
          cur   <- count.get
        yield cur
      }.assert(n => assertTrue(n == 7)),
    ),
    section("Modules")(
      md"""
| Artifact | Role |
| -------- | ---- |
| `ascent-datastar` | Protocol + `SignalStore` (JVM / JS / Native) |
| `ascent-datastar-js` | Browser: EventSource → store / DOM, action fetch |
| `ascent-datastar-http` | Server: `AscentDatastar` over zio-http datastar SDK |

See [Datastar HTTP](datastar-http.html) for SSE routes and [Hybrid regions](hybrid-regions.html)
for `serverRegion` + `patchRegion`.
""",
      example {
        E.p(
          "Client apps depend on ",
          E.code("ascent-datastar-js"),
          "; servers on ",
          E.code("ascent-datastar-http"),
          ".",
        )
      }.assert(_ => assertTrue(true)),
    ),
  )
end DatastarPage
