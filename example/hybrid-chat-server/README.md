# hybrid-chat-server

The JVM backend for the [`hybrid-chat`](../hybrid-chat/) example. It owns the message list and drives
the client's `serverRegion("messages")` — "the chat interaction itself, server-side" — while the rest
of the UI is normal client ascent.

Built on [`ascent-datastar-http`](../../datastar-http/): message rows are authored in the typed ascent
DSL ([`MessageView`](src/main/scala/example/chat/MessageView.scala)), rendered to HTML via
[`ascent-html`](../../html/), and pushed with `AscentDatastar.patchRegion`.

## Routes

| Route | What it does |
|-------|--------------|
| `GET /chat/sse` | Opens a datastar stream: pushes the message region + a `typing` signal now, then again on every change (a `Hub` pulses on each state change). |
| `POST /chat/send` | Reads the client's `{username, message}` signals and appends a message. |
| `POST /chat/typing` | Marks the user typing; auto-cleared after a few seconds. |

## Layout

| File | Responsibility |
|------|----------------|
| `Message.scala` | The `Message` model + the `MessageRequest` / `TypingRequest` / `JoinRequest` signal payloads (`derives Schema` for `readSignals`). |
| `ChatRoom.scala` | In-memory state (`Ref[List[Message]]`, `Ref[Set[String]]`) + a `Hub[Unit]` that pulses on every change so an open SSE stream re-pushes. |
| `MessageView.scala` | The message list as an ascent `UI` — display-only, authored in the same DSL the client uses. |
| `ChatServer.scala` | `ZIOAppDefault`: wires the routes, configures brotli/gzip compression, serves on `:8080`. |

Run with `sbt hybridChatServer/run` alongside the client's `npm run dev`. See the
[client README](../hybrid-chat/README.md) for the full walkthrough.
