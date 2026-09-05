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
  with zio-http brotli compression, and calls [`Preview.serve`](../../preview/) so the spliced
  client is same-origin on `:8080`.

## Routes

| Route | What it does |
|-------|--------------|
| `GET /sse` | datastar stream: pushes the initial `count` signal, then one push per change. |
| `POST /increment` | bumps the count; the change is pushed to every open stream. |

## Run it

Two terminals. `sbt run` cwd is the repo root, so the server looks for
`example/datastar-app/target/preview` (override with a path argument).

```bash
sbt ~datastarExampleJS/ascentPreview     # splice + stamp; relink on change
sbt datastarExampleServer/run            # Preview.serve + API on :8080
```

Open http://localhost:8080. After editing a client `.scala`, the watch restages and the tab
reloads.

## What to notice

Click the button: it POSTs to the server, the server bumps the count and pushes a `patch-signals`
frame, and ascent repaints **just the count text node** via `Source.set` — focus in any sibling input
is preserved. The signal channel maps straight onto ascent's reactive primitive, so no morph is
involved.
