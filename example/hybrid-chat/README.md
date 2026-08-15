# hybrid-chat

A chat app that proves the **hybrid** model: the chrome — layout, inputs, send button, typing
indicator — is **normal client-side ascent** (reactive `Squawk`s, two-way-bound inputs, ascent event
handlers), while only the **message list** is server-driven. The list is a
[`serverRegion("messages")`](../../core/) the server fills by rendering an ascent `UI` to HTML and
pushing it via `patchRegion`.

Two pieces:

- **`example/hybrid-chat`** (this dir, JS) — the client. Pure ascent: `import ascent.*` /
  `import ascent.dsl.*`. Declares the region + the chrome, connects the datastar SSE stream, posts the
  send/typing actions.
- **[`example/hybrid-chat-server`](../hybrid-chat-server/)** (JVM) — the zio-http backend. Owns the
  `ChatRoom` state; renders message rows via [`ascent-html`](../../html/) and pushes them into the
  client's region with [`AscentDatastar.patchRegion`](../../datastar-http/). Preview routes are
  composed in so the spliced client is same-origin on `:8080`.

The same `MessageView` is authored in the typed ascent DSL on the server — the server is "an ascent
client" for that region.

## Run it

Two terminals. `sbt run` cwd is the repo root, so the server looks for
`example/hybrid-chat/target/preview` (override with a path argument).

```bash
sbt ~hybridChatJS/previewStage           # splice + stamp; relink on change
sbt hybridChatServer/run                 # static + SSE reload + API on :8080
```

Open http://localhost:8080. After editing a client `.scala`, the watch restages and the tab reloads.

## What to notice

- Type a name + a message and press **Enter** (or click **Send**): the client `POST`s its
  `{username, message}` signals; the server appends the message and **re-pushes** the whole region;
  the new row appears.
- The message input **clears** after sending (client `message.set("")`) while the username **persists**
  — the client-side Squawk state is independent of the server-owned region, which ascent never
  reconciles internally.
- Start typing and another connected client sees the **typing indicator** — that rides the
  `patch-signals` channel (`typing` is a read-only signal the server pushes).
