# ascent-datastar-http

The **server-side** idiomatic wrapper over the official
[`zio-http-datastar-sdk`](https://github.com/zio/zio-http). It makes a zio-http server "an ascent
client": render an ascent `UI` subtree to HTML via [`ascent-html`](../html/) and push it through the
SDK's `ServerSentEventGenerator` as a granular `patch-elements`, or push typed `patch-signals`.

> JVM only (the SDK + zio-http are JVM). Depends on `html` and `datastar`, plus
> `zio-http-datastar-sdk` (pinned to `3.11.0` — the newest version on Maven Central; latest zio-http
> is `3.11.3` but the SDK lags).

You author views **once** in ascent's typed DSL + CSS-in-Scala; the SDK owns the SSE transport, signal
reading, and (via zio-http) compression. A datastar user keeps the SDK's `events { }` /
`req.readSignals` idiom and only swaps `template2` for ascent views.

```scala
import ascent.datastar.http.AscentDatastar
import zio.http.datastar.*

events {
  handler { (req: Request) =>
    for
      _ <- AscentDatastar.patchRegion("messages", MessageView.list(msgs))  // ascent UI → #messages
      _ <- AscentDatastar.patchSignal("typing", "Alice is typing…")        // typed signal
    yield ()
  }
}
```

## API (`object AscentDatastar`)

| Member | What it pushes |
|--------|----------------|
| `patch(ui, selector, mode = Outer)` | render `ui`, push as `patch-elements` at `selector` |
| `patch(ui)` | render `ui`, push with the protocol's id-fallback (no explicit selector) |
| `patchRegion(id, ui, mode = Inner)` | render `ui` into a client's `serverRegion(id)` — targets `#id`; the id is the exact address the client mounted, so the two sides agree by construction |
| `patchSignal(name, value)` | push one named signal as `{name: value}` (value JSON-encoded) |
| `patchSignalsJson(json)` | push a raw signals `{…}` object |

`patchRegion` is the idiomatic way to drive a server-owned region from the server — it pairs with the
client's [`serverRegion(id)`](../core/) boundary.

## Compression

Brotli/gzip are **server config**, not a dependency of this module — configure zio-http's
`responseCompression`. Note Netty's brotli needs `brotli4j` on the classpath, and brotli's `lgwin`
must be set explicitly (the default `-1` throws):

```scala
Server.Config.CompressionOptions.brotli(quality = 8, lgwin = 24)
```

See [`example/hybrid-chat-server`](../example/hybrid-chat-server/) and
[`example/datastar-app-server`](../example/datastar-app-server/) for full working servers.
