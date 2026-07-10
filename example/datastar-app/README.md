# datastar-app

The smallest end-to-end proof of the **datastar loop**: a server-driven counter. The client is **pure
ascent** — a [`SignalStore`](../../datastar/) fed by the datastar SSE stream drives ascent's own
reactive AST; a button POSTs an action back.

Two pieces:

- **`example/datastar-app`** (this dir, JS) — the client. `store.squawk("count", 0)` gives a
  `Squawk[Int]` bound to a `ReactiveText`; [`DatastarClient.connect("/sse", store)`](../../datastar-js/)
  opens the stream inside a `scoped` boundary; a button calls `Action.post(store, "/increment")`.
- **[`example/datastar-app-server`](../datastar-app-server/)** (JVM) — the zio-http backend. Holds the
  count, serves the SSE stream + the increment action via [`ascent-datastar-http`](../../datastar-http/),
  with zio-http brotli compression.

## Routes

| Route | What it does |
|-------|--------------|
| `GET /sse` | datastar stream: pushes the initial `count` signal, then one push per change. |
| `POST /increment` | bumps the count; the change is pushed to every open stream. |

## Run it

```bash
sbt datastarExampleServer/run            # zio-http on :8080

cd example/datastar-app
npm install                              # first time only
npm run dev                              # links datastarExampleJS via sbt, opens http://localhost:5173
```

Vite proxies `/sse` and `/increment` to `:8080`. After editing a client `.scala`, relink with
`sbt datastarExampleJS/fastLinkJS` before reloading.

## What to notice

Click the button: it POSTs to the server, the server bumps the count and pushes a `patch-signals`
frame, and ascent repaints **just the count text node** via `Source.set` — focus in any sibling input
is preserved. The signal channel maps straight onto ascent's reactive primitive, so no morph is
involved.
