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

Html.render(ui)        // URIO[R, String]: just the markup
Html.renderPage(ui)    // URIO[R, Page]: markup plus the collected stylesheet
```

A whole document is a `UI` too. `Document(title)(head*)(body*)` builds the shell (charset, viewport, title, then your head nodes, then the body). `renderDocument` mounts an `html` root as the document element and emits the doctype. Head and body are separate lists: a `script` in the first list stays in `head`, and the same tag in the second list stays in `body`.

```scala
val host = Document("Todos host")()(E.script(A.src("/host.js")))

Html.renderDocument(host, StylePlacement.Aside) // ZIO[R, Html.Error, Page]
```

`A.*` keys are the HTML content attributes, typed at the element that introduces them. `E.meta(A.content(...))` typechecks. `E.div(A.content(...))` does not. Global attributes (`id`, `class`, `lang`, …) are usable on every element. An event or a hand-built `Attr` is accepted everywhere.

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
| `Html.render(ui, idMode?)` | `URIO[R, String]` | fragment markup. Compact `innerHTML`, no doctype. The morph form. |
| `Html.renderPretty(ui, idMode?)` | `URIO[R, String]` | indented fragment. For humans. Not the morph form. |
| `Html.renderPage(ui, idMode?)` | `URIO[R, Page]` | `Page(html, css)`. CSS from this render's `StyleRegistry`. |
| `Html.renderPagePretty(ui, idMode?)` | `URIO[R, Page]` | pretty fragment plus the same CSS. |
| `Html.renderDocument(ui, placement, idMode?)` | `ZIO[R, Html.Error, Page]` | doctype plus the `html` root. `placement` is required. |
| `Html.renderDocumentPretty(ui, placement, idMode?)` | `ZIO[R, Html.Error, Page]` | indented document plus the same CSS. Not the morph form. |
| `Document(title, lang = "en")(head*)(body*)` | `UI[R]` | charset, viewport, title, then `head`, then `body`. |
| `StylePlacement` | `InlineInHead` or `Aside` | where the collected CSS goes. Both return it on `Page`. |
| `Html.Error` | `NotHtmlRoot(tags)` or `NoHead` | the tree was not a single `html` element, or `InlineInHead` found no `head`. |
| `Html.Page` | `case class Page(html, css)` | rendered markup plus its stylesheet. |

`idMode` defaults to `IdMode.HashWithRegistry` (the Mount default). Leave it unless you need a
different id scheme.

`StylePlacement.Aside` leaves `head` alone. `StylePlacement.InlineInHead` appends one `style` at the
end of `head` when the CSS is non-empty, and fails with `Html.Error.NoHead` when the mounted tree
has no `head`. Compact document markup is what a consumer diffs. Pretty output is indented for
humans; do not feed it back through morph.

Used by [`ascent-datastar-http`](../datastar-http/) to render UI subtrees the server pushes as
datastar `patch-elements`.
