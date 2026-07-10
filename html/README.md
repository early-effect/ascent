# ascent-html

A UI → HTML **string** renderer — a cross-platform sibling of the JS `Mount` engine. It consumes the
exact same `UI` / `Attr` AST but produces a `String` instead of live DOM nodes, so you author a view
**once** and render it on the server (or any JVM/JS/Native host) for SSR or server-driven updates.

> Standalone SSR with **zero datastar dependencies**. Depends only on `core` (the AST + `Squawk`) and
> `css` (for collecting a tree's CSS). Cross-compiled to JVM / JS / Native.

```scala
import ascent.*
import ascent.dsl.*
import ascent.html.Html

val ui = E.div(A.className("card"), E.h1("Hello"), E.p("rendered on the server"))

Html.render(ui)        // URIO[R, String]  — just the markup
Html.renderPage(ui)    // URIO[R, Page]    — (html, css): markup + the collected stylesheet
```

## What it does

- **Snapshots reactive boundaries.** `ReactiveText` / `ReactiveChild` / `When` / `ForEach` /
  `ForEachSignal` are `.get`-ted for their *current* value and rendered inline — a single static
  snapshot, no observers, no `Cleanup`. (`Squawk.get` is an effect, hence the `URIO`.)
- **Skips what a server has no use for.** Event handlers and lifecycle hooks (`OnMount` / `OnUnmount`)
  are omitted; `Scoped` runs its builder once in a fresh `zio.Scope`, renders, and closes.
- **Stamps `data-ascent` ids identically to `Mount`.** Same `AstId` / `IdAssigner` with the same
  default `IdMode`, so **server ids == client ids** — the basis for future hydration and the addresses
  patch-elements target.
- **Encodes correctly.** Text/attribute escaping (`&<>"`), boolean-attr presence/omission, `class`
  token merging, and void-element self-closing all mirror `Mount`'s encoding so the two sides agree.

## API

| Member | Result | Notes |
|--------|--------|-------|
| `Html.render(ui, idMode?)` | `URIO[R, String]` | the markup for an arbitrary subtree |
| `Html.renderPage(ui, idMode?)` | `URIO[R, Page]` | `Page(html, css)` — CSS collected via `StyleSink.capturing` |
| `Html.Page` | `case class Page(html, css)` | rendered markup + its stylesheet |

`idMode` defaults to `IdMode.HashWithRegistry` (the Mount default) — leave it unless you need a
different id scheme.

Used by [`ascent-datastar-http`](../datastar-http/) to render UI subtrees the server pushes as
datastar `patch-elements`.
