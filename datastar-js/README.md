# ascent-datastar-js

The **browser-side datastar runtime**: ascent's own Mount + Squawk engine driving the DOM in place of
the ~12 KB `datastar.js`. "ascent implements the datastar interface" — it opens an `EventSource`,
routes incoming frames into the [`SignalStore`](../datastar/) (signals → `Source.set` → ascent
boundaries repaint) and into the DOM (patch-elements → selector + mode), and dispatches actions back
via `fetch`.

> JS only — the one piece that touches the live DOM facade. Depends on `datastar` (protocol + store),
> `js` (Mount / `Cleanup` machinery), and `dom-facade` (`EventSource` / `fetch`).

## Connect

`connect` ties the whole connection to the ambient `zio.Scope` — `close()` runs when the scope closes
(e.g. when a `scoped { … }` subtree unmounts), so nothing leaks.

```scala
import ascent.datastar.SignalStore
import ascent.datastar.js.{Action, DatastarClient}

for
  store <- SignalStore.make()
  count <- store.squawk("count", 0)
  ui = E.body(scoped {
         DatastarClient.connect("/sse", store).as {       // signals → Squawk, elements → DOM
           E.div(
             E.span(count.map(_.toString)),
             E.button(Ev.onClick(_ => Action.post(store, "/increment")), "+"),
           )
         }
       })
  _ <- Mount.mountBody(ui)
yield ()
```

## Two channels

- **`patch-signals` (idiomatic):** routed into the `SignalStore`, decoded + Eq-deduped, then
  `Source.set`. ascent's `ReactiveText` / `ForEach` / reactive attrs repaint surgically with **focus
  and caret preserved** — no morph involved. This is the recommended path.
- **`patch-elements` (server-rendered regions):** an incoming HTML fragment is applied into a selector
  with a mode (`inner` / `outer` / `replace` / `append` / `prepend` / `before` / `after` / `remove`).
  `Morph.scala` provides an id/key-aware morph that reuses existing nodes — preserving focus, caret,
  and scroll across a re-patch of a server-owned region. Pairs with the
  [`serverRegion(id)`](../core/) AST node: a boundary ascent mounts as an empty container it does
  **not** reconcile internally; its contents come from server patches addressed to `#id`.

## Actions

`Action.post(store, url)` / `Action.get(store, url)` snapshot the store's signals and send them in the
format the server's `readSignals` expects, with the `Datastar-Request` header. Both are total — errors
are logged, the effect still succeeds.

| File | Responsibility |
|------|----------------|
| `DatastarClient.scala` | `connect` — open the `EventSource`, register per-event listeners per dialect, route frames, tear down on scope close. |
| `ElementPatching.scala` | Apply a decoded `PatchElements` into the DOM by selector + mode; reports diagnostics for missing/illegal targets. |
| `Morph.scala` | The id/key-aware morph that backs the `outer` / `inner` modes. |
| `Action.scala` | Outgoing action dispatch (`post` / `get`) from ascent event handlers. |
| `EventSourceJS.scala` | Tiny hand-written `EventSource` constructor facade (the generated one has none). |
