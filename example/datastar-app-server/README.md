# datastar-app-server

The JVM backend for the [`datastar-app`](../datastar-app/) counter example. It holds the count and
serves the datastar SSE stream + the increment action through
[`ascent-datastar-http`](../../datastar-http/), with zio-http's built-in brotli compression.

## Routes

| Route | What it does |
|-------|--------------|
| `GET /sse` | `events { handler { … } }` opens the datastar stream: pushes the initial `count` signal, then one push per change. |
| `POST /increment` | bumps the count; the change is pushed to every open stream. |

## Run it

```bash
sbt datastarExampleServer/run            # zio-http on :8080
```

Then start the client with `cd example/datastar-app && npm run dev`. See the
[client README](../datastar-app/README.md) for the full walkthrough.

> Netty's brotli compression needs `brotli4j` on the classpath (zio-http doesn't bundle it), and
> brotli's `lgwin` must be set explicitly — both are handled in this module's build settings and
> server config.
