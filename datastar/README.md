# ascent-datastar

The **[datastar](https://data-star.dev/) protocol core** — DOM-free and platform-neutral, so the
routing / decoding / merge logic is plain JVM-unit-testable code. It models the wire events, defines a
dialect SPI, and holds the `SignalStore` that turns incoming signal patches into ascent `Squawk`
updates.

> Depends only on `core` (for `Squawk`) plus `zio-json`. No DOM, no zio-http. Cross-compiled to
> JVM / JS / Native. The browser runtime ([`ascent-datastar-js`](../datastar-js/)) and the server
> wrapper ([`ascent-datastar-http`](../datastar-http/)) build on this.

## The headline idea

datastar's `patch-signals` channel maps directly onto ascent's reactive primitive: the server pushes a
typed **signal delta**, the `SignalStore` routes it by name into the matching `Source`, and a
`Source.set` makes ascent's fine-grained boundaries repaint surgically — **focus and caret preserved
natively, no idiomorph needed.** This is the idiomatic path and where ascent beats a generic datastar
client.

## What's here

| File | Responsibility |
|------|----------------|
| `Patch.scala` | `RemoteEvent` (`PatchSignals` / `PatchElements`) — the decoded wire model — and `SignalPatch` (`Put` / `Delete`), the per-name flattening of a signals object. |
| `ElementPatchMode.scala` | `enum ElementPatchMode { Outer, Inner, Replace, Append, Prepend, Before, After, Remove }` — matches the SDK's enum. |
| `RemoteDialect.scala` | The SPI: `eventNames` + `parse(eventName, data): Either[String, RemoteEvent]`. Total — malformed input is a `Left`, never a throw. |
| `Datastar.scala` | `object Datastar extends RemoteDialect` — recognizes `datastar-patch-signals` (with `onlyIfMissing`, RFC-7386 null-delete) and `datastar-patch-elements` (selector + mode). |
| `JsonMerge.scala` | RFC-7386 JSON merge-patch, applied to raw `Json` before decoding. |
| `SignalStore.scala` | Named, typed, client-owned `Source`s fed by addressed signal patches. |

## SignalStore

A view declares each signal it cares about and binds the returned `Squawk` to the AST like any other:

```scala
for
  store <- SignalStore.make()
  count <- store.squawk("count", 0)          // read-only Squawk[Int] for display
  draft <- store.source("draft", "")          // WRITABLE Source[String] for two-way binding
yield E.div(
  E.span(count.map(_.toString)),
  E.input(A.value(draft), Events.onInput(e => draft.set(e.targetValue.getOrElse("")))),
)
```

Incoming `patch-signals` are routed by name (`store.route` / `routeAll`), RFC-7386-merged at the JSON
level, decoded to `A`, then `Source.set` with the usual `Eq` dedup. `store.snapshot` serializes all
current values as the JSON an outgoing action sends. Design choices baked in:

- **Statically declared** — a signal exists once a view calls `squawk`; a patch for an unknown name is
  a logged no-op (never silently stores untyped JSON).
- **Decode-failure survives** — a value that doesn't decode to `A` retains the prior value and surfaces
  the error; the connection keeps working.
- **Delete resets to init** — an RFC-7386 `null` resets the signal to its declared initial value,
  keeping the `Source[A]` total (no `Option` ceremony).
- **`source` shares one cell** — `source(name, init)` returns the *writable* `Source` for client edits;
  it's the same cell server pushes drive, so edits and pushes share state.
