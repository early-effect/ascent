package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Browser mount entry, lifecycle, sync handlers. */
object Mounting extends DocSpec:

  def doc = page("Mounting")(
    md"""
`ascent-js` is the browser mount/binding engine. Apps call `AscentApp.mount(ui, parent)` or
`AscentApp.mountBody(ui)`; that supplies the JS `DomOps` and `DomStyleSink` to the cross-platform
`Mount` walker so you never name those pieces at the call site.
""",
    section("Entry points")(
      md"""
```scala
import ascent.*
import ascent.dsl.*

AscentApp.mount(ui, parent)       // into an element
AscentApp.mountBody(E.body(...))  // replace document body
```

`Mount` walks the AST once and wires reactive boundaries. In-memory slots own the DOM nodes per
boundary (no comment anchors). Event dispatch runs handlers via `runtime.unsafe.runOrFork`: the
synchronous prefix runs inline (needed for `preventDefault`); the first suspension forks to the
next macrotask.
""",
      example {
        E.div(A.id("app"), "mount target")
      }.assert(_ => assertTrue(true)),
    ),
    section("Lifecycle")(
      md"""
`Attr.OnMount` fires after insertion (so `getBoundingClientRect` / canvas work). `Attr.OnUnmount`
runs LIFO at unmount / swap / key-drop. Prefer `scoped { … }` for resource lifetimes tied to a
subtree.
""",
      exampleIO {
        for ready <- sq(false)
        yield E.div(
          Attr.OnMount[Any](_ => ready.set(true)),
          when(ready)(E.span("mounted")),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Sync handlers")(
      md"""
Use `Ev.sync.onDragStart { … }` (and friends) when side effects must run on the dispatch stack
before the browser continues the event.
""",
      example {
        E.div(A.draggable(true), "drag me")
      }.assert(_ => assertTrue(true)),
    ),
  )
end Mounting
