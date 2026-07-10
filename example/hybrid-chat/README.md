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
  client's region with [`AscentDatastar.patchRegion`](../../datastar-http/).

The same `MessageView` is authored in the typed ascent DSL on the server — the server is "an ascent
client" for that region.

## Run it

Start the backend (JVM) and the Vite-served client in two terminals:

```bash
sbt hybridChatServer/run                 # zio-http on :8080 (SSE + send/typing routes, brotli)

cd example/hybrid-chat
npm install                              # first time only
npm run dev                              # links hybridChatJS via sbt, opens http://localhost:5173
```

Vite proxies `/chat/sse`, `/chat/send`, `/chat/typing` to `:8080`, keeping client and server
same-origin. After editing a client `.scala`, relink with `sbt hybridChatJS/fastLinkJS` before
reloading.

## What to notice

- Type a name + a message and press **Enter** (or click **Send**): the client `POST`s its
  `{username, message}` signals; the server appends the message and **re-pushes** the whole region;
  the new row appears.
- The message input **clears** after sending (client `message.set("")`) while the username **persists**
  — the client-side Squawk state is independent of the server-owned region, which ascent never
  reconciles internally.
- Start typing and another connected client sees the **typing indicator** — that rides the
  `patch-signals` channel (`typing` is a read-only signal the server pushes).
