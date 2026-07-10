# ascent

[![Scala CI](https://github.com/early-effect/ascent/actions/workflows/scala.yml/badge.svg)](https://github.com/early-effect/ascent/actions/workflows/scala.yml)
[![Maven Central](https://img.shields.io/maven-central/v/rocks.earlyeffect/ascent-core_3?logo=apachemaven)](https://central.sonatype.com/artifact/rocks.earlyeffect/ascent-core_3)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A reactive UI library for **Scala 3** that renders directly to the DOM — no virtual DOM, no
diffing. ascent builds a pure UI tree once, then surgically patches the exact node, attribute, or
child-list behind each reactive boundary. The reactive substrate is **ZIO**, so effects, typed
errors, and resource lifetimes are first-class rather than bolted on.

> **Status: early / pre-1.0.** Published under [early-semver](https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme)
> (`versionScheme := "early-semver"`) — the API can change between minor versions until `1.0`. See [Status](#status).

```scala
import ascent.*
import ascent.dsl.*

val counter =
  for count <- sq(0)
  yield E.div(
    E.button(Ev.onClick(_ => count.update(_ - 1)), "-"),
    E.span(count.map(_.toString)),                       // only this text node re-renders
    E.button(Ev.onClick(_ => count.update(_ + 1)), "+"),
  )
```

---

## Why ascent

Conventional virtual-DOM frameworks re-render a subtree and diff it on every change — which
rebuilds nodes that didn't change and, in practice, loses input focus and caret position on every
keystroke. ascent takes the opposite approach: the UI is a value, reactive values (`Squawk`s) mark
the boundaries that can change, and the engine touches **only** those boundaries. A `<li>` that
isn't changing is never rebuilt; an `<input>` you're typing into keeps its focus and selection.

Three things it optimizes for:

- **Developer ergonomics** — a terse, two-import DSL that reads like the HTML it produces.
- **Type safety** — elements, attributes, ARIA, events, and CSS are all typed; mismatches are
  compile errors, not runtime surprises.
- **An effect system you can lean on** — `Squawk` and the optional `conduit` state integration are
  ZIO all the way down, so state, side effects, and teardown compose with `for`.

---

## Developer ergonomics

A view file needs exactly two imports:

```scala
import ascent.*        // elements, attrs, aria, events, css authoring, Ctx, Squawk, … + aliases
import ascent.dsl.*    // the builder DSL: el/attr `apply`, when / forEach / scoped / fragment
```

`import ascent.*` unions whatever ascent modules are on your classpath (it's an *open* package
shared across the jars), so you never reach into `ascent.domtypes` / `ascent.css` / `ascent.js`
by hand. Both the descriptive names and short aliases are available — pick whichever reads best:

| Alias | Full name   | Example              |
|-------|-------------|----------------------|
| `E`   | `Elements`  | `E.div`, `E.input`   |
| `A`   | `Attrs`     | `A.className`, `` A.`type` `` |
| `Aria`| `AriaAttrs` | `Aria.role`, `Aria.ariaLabel` |
| `Ev`  | `TypedEvents` | `Ev.onClick`, `Ev.onInput` |
| `S`   | `Styles`    | `S.color`, `S.padding.px(8)` |

Elements, attributes, and children compose through one uniform `apply`. A bare `String` becomes a
text node; a `Squawk[String]` becomes a reactive text node; an attribute key applied to a `Squawk`
becomes a reactive attribute — all without ceremony:

```scala
E.label(
  A.className("title"),
  Ev.onDblClick(_ => startEditing),
  todoText,                          // Squawk[String] → live-updating text node
)
```

Control flow is just functions that return `UI`:

```scala
when(isEditing)(editor)              // reactive: mounts/unmounts as the Squawk[Boolean] flips
forEach(items)(_.id)(renderRow)      // keyed list: reuses DOM nodes across reorders
```

## Type safety

Everything you place in the tree is typed against the real web platform — the catalogs are
**generated from the vendored W3C `webref` data** (no npm, pinned snapshot), so they track the
actual spec:

- **Attributes carry their value type and codec.** `A.checked` is `AttrKey[Boolean]`,
  `A.autofocus` encodes as a presence flag, `Aria.ariaPressed` serializes to `"true"`/`"false"`.
  Passing the wrong type doesn't compile.
- **Events are typed.** `Ev.onClick` hands your handler a `dom.PointerEvent`, `Ev.onKeyDown` a
  `dom.KeyboardEvent` — no `js.Dynamic` casts at the call site. For the common two-way-binding
  needs there's an ergonomic, platform-neutral event with `targetValue` / `key`:
  ```scala
  Events.onInput(e => setDraft(e.targetValue.getOrElse("")))
  Events.onKeyDown(e => if e.key.contains("Enter") then submit else ZIO.unit)
  ```
- **CSS is typed too.** `S.padding.px(8)`, `S.display.flex`, `S.color.rgba(255, 0, 170, 0.6)` —
  typed value grammars per property, with class names derived automatically:
  ```scala
  object Card extends CssClass(S.padding.px(16), Selector(":hover", S.color("cyan")))
  E.div(Card.toAttr, "hello")
  ```
- **Void elements reject children at compile time**, and the DOM facade (`ascent.dom`) is our own
  typed `@js.native` layer — ascent does not depend on `scalajs-dom`.

## The effect system, and conduit integration

`Squawk[A]` is ascent's reactive value-over-time. Constructing a mutable source is an effect
(`sq(0): UIO[Source[Int]]`); `set` / `update` / `observe` are effects; `map` and derived
combinators are pure and lazy, so the DSL stays clean. Changes are deduped by a pluggable
`Eq[A]` — an `Eq`-equal write is a no-op, so observers never see spurious updates. Every reactive
boundary registers a paired teardown; nothing leaks when a subtree unmounts.

For application state, ascent ships an **optional** bridge to
[conduit](https://github.com/russwyte/conduit), a ZIO-based unidirectional, lens-keyed immutable
store. Because both sides are ZIO, the bridge composes natively — and views never see conduit at
all. A view receives a `Ctx[M]` handle and speaks two verbs: read a reactive slice, dispatch an
action.

```scala
def component(ctx: Ctx[TodoApp.Model]) =
  for draft <- ctx.squawk(_.draft)               // reactive Squawk of a (possibly nested) slice
  yield E.input(
    A.value(draft),
    Events.onInput(e => ctx(TodoApp.Action.SetDraft(e.targetValue.getOrElse("")))),
  )
```

`Ctx` exposes the full power of conduit's optics as plain field paths — nested reach
(`ctx.squawk(_.a.b.c)`), whole-model (`ctx.model`), one-shot reads (`ctx.read(_.path)`), and
**element-scoped** subscriptions for collections:

```scala
forEach(visible)(_.id) { t =>
  scoped {                                        // opens a ZIO Scope tied to this row's lifetime
    ctx.squawkKey(_.todos, t.id).map { item =>    // subscribes to just this map entry
      TodoItem.render(ctx)(t.id, item.map(_.getOrElse(t)))
    }
  }                                               // row leaves → Scope closes → listener unsubscribes
}
```

A change to a sibling key never wakes this row, and a churning list never accumulates dead
listeners — the `scoped { … }` boundary (a `zio.Scope`) guarantees the conduit subscription is
released exactly when the row unmounts. Application state and actions live in one model file; view
files import only `ascent.*`.

---

## Installation

ascent is published to Maven Central under the `rocks.earlyeffect` group. It's cross-built for
**JVM, Scala.js, and Scala Native**, so use `%%%` (which picks the right platform artifact) in a
`sbt-crossproject` / Scala.js / Native build:

```scala
libraryDependencies += "rocks.earlyeffect" %%% "ascent-core" % "<version>"  // Squawk + UI AST + DSL (all platforms)
libraryDependencies += "rocks.earlyeffect" %%% "ascent-js"   % "<version>"  // browser mount engine (Scala.js)
libraryDependencies += "rocks.earlyeffect" %%% "ascent-css"  % "<version>"  // typed CSS-in-Scala (all platforms)
```

Other modules follow the same `ascent-<module>` naming (e.g. `ascent-html`, `ascent-conduit`,
`ascent-datastar`) — see the module table below. On a plain JVM-only build use `%%` instead of `%%%`.

## Status

ascent is early and evolving. It's well-tested (1000+ zio-test cases across JVM/JS/Native, leaning
on negative and pathological cases) and used to build real apps, but pre-1.0 the API is not frozen —
expect breaking changes between minor versions (that's what the `early-semver` scheme signals).

A few things to know before adopting:

- **Some modules are low-level plumbing.** `ascent-dom-core`, `ascent-mount-engine`, and
  `ascent-dom-facade` are engine internals that other modules depend on transitively — they're
  published so consumers resolve, not because you'll typically depend on them directly. Most apps
  use `ascent-core` + `ascent-js` (+ `ascent-css`, and `ascent-conduit` for state).
- **Docs are a work in progress.** Full documentation is coming — authored as runnable, tested
  examples and rendered with ascent itself, via the forthcoming
  [specular](https://github.com/early-effect) tests-as-docs generator (a docs page is a Scala
  `Spec` that both asserts in CI and SSR-renders through ascent, so examples can't drift). For now,
  the [example app](#run-the-example) and the module table below are the best starting points.

## Run the example

`example/` holds one self-contained app per subdirectory (more coming). The first,
`todo-conduit`, is a synthwave-glass [TodoMVC](https://todomvc.com/) over conduit, served by
Vite. The app is Scala.js, so it has to be linked by sbt — the
`@scala-js/vite-plugin-scalajs` plugin runs the sbt link step for you and rewires the
`scalajs:main.js` import to its output:

```bash
cd example/todo-conduit
npm install                    # first time only
npm run dev                    # invokes `sbt todoConduitJS/fastLinkJS`, then opens http://localhost:5173
```

`npm run dev` triggers `sbt todoConduitJS/fastLinkJS` on demand — so the first load (and the first
load after a `.scala` edit) waits on sbt to relink. You can also run the link step directly, e.g.
to build the JS without a browser, or to pre-warm sbt before serving:

```bash
sbt todoConduitJS/fastLinkJS      # one-shot link
sbt "~todoConduitJS/fastLinkJS"   # watch mode: relink on every change
```

Try the TodoMVC: add todos, toggle and edit them (double-click a row), switch the All / Active /
Completed filters, clear completed. Notice that editing a row preserves caret position and that
toggling one item doesn't rebuild the others — that's the surgical patching.

## Build & test

```bash
sbt test                       # full cross-platform suite (JVM / JS / Native)
sbt todoConduitJS/fastLinkJS   # link the example without a browser
```

ascent is built with Scala 3 and cross-compiled to **JVM, Scala.js, and Scala Native** via
`sbt-projectmatrix`. The module layout:

| Module          | What it is                                                        |
|-----------------|-------------------------------------------------------------------|
| `dom-types`     | Zero-dependency typed element / attribute / event / ARIA catalogs |
| `core`          | The `UI` AST, the `Squawk` reactive primitive, and the DSL        |
| `dom-facade`    | Our own typed `@js.native` DOM facade (no `scalajs-dom`)          |
| `js`            | The DOM mount/binding engine, typed events, canvas helper         |
| `css`           | CSS-in-Scala authoring (typed properties, classes, at-rules)      |
| `conduit`       | Optional bridge to the conduit state store (`Ctx[M]`)             |
| `html`          | UI → HTML string renderer — standalone SSR ([readme](html/README.md)) |
| `datastar`      | [datastar](https://data-star.dev/) protocol core + `SignalStore` ([readme](datastar/README.md)) |
| `datastar-js`   | Browser datastar runtime: SSE → Squawk / DOM, action dispatch ([readme](datastar-js/README.md)) |
| `datastar-http` | Server wrapper over `zio-http-datastar-sdk` ([readme](datastar-http/README.md)) |
| `domgen`        | JVM-only generator that emits the typed catalogs from W3C webref ([readme](domgen/README.md)) |
| `example/*`     | One self-contained app per subdir (e.g. `todo-conduit`)           |

Runtime dependencies are kept deliberately small: `core` needs only ZIO and the zero-dep
`dom-types`; conduit is opt-in.

## License

ascent is licensed under the [Apache License 2.0](LICENSE).
