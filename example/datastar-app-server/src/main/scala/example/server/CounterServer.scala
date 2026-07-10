package example.server

import ascent.datastar.http.AscentDatastar
import zio.*
import zio.http.*
import zio.http.datastar.*

/** The zio-http backend for the datastar counter example — "the server is an ascent client."
  *
  *   - holds the count in a `Ref`, with a `Hub` that pulses on every change;
  *   - `GET /sse` opens a datastar SSE stream: it pushes the current count immediately, then a fresh `patch-signals` on
  *     every subsequent change (the chat-example pattern);
  *   - `POST /increment` reads nothing from the body — it just bumps the count and pulses the hub;
  *   - response compression (brotli/gzip) is enabled via zio-http's own `Server.Config`.
  *
  * The client (a pure-ascent Scala.js app, served by Vite in dev) turns each pushed signal into a `Squawk` and lets
  * ascent's Mount engine repaint — no datastar.js.
  */
object CounterServer extends ZIOAppDefault:

  final case class State(count: Ref[Int], pulse: Hub[Unit])

  private def bump(state: State): UIO[Unit] =
    state.count.update(_ + 1) *> state.pulse.publish(()).unit

  /** Push the current count as a `patch-signals` frame. */
  private def pushCount(state: State): ZIO[Datastar, Nothing, Unit] =
    state.count.get.flatMap(c => AscentDatastar.patchSignal("count", c))

  private def routes(state: State): Routes[Any, Nothing] =
    Routes(
      // Open the SSE stream: initial value, then one push per change. `events { handler { ... } }`
      // wraps a handler whose body requires `Datastar` (+ Scope, which `events` supplies) into an
      // SSE `Response` — the chat-example shape.
      Method.GET / "sse" -> events {
        handler { (_: Request) =>
          for
            _      <- pushCount(state)
            stream <- state.pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
            _      <- stream.mapZIO(_ => pushCount(state)).runDrain
          yield ()
        }
      },
      // The increment action: bump and pulse; the open SSE stream pushes the new value.
      Method.POST / "increment" -> handler { (_: Request) =>
        bump(state).as(Response.ok)
      },
    ).sandbox

  private val compression =
    Server.Config.ResponseCompressionConfig(
      contentThreshold = 0,
      options = IndexedSeq(
        // Explicit quality/lgwin — Netty rejects the default lgwin (-1); valid window is 10-24.
        Server.Config.CompressionOptions.brotli(quality = 8, lgwin = 24),
        Server.Config.CompressionOptions.gzip(),
      ),
    )

  def run =
    for
      count <- Ref.make(0)
      pulse <- Hub.unbounded[Unit]
      state = State(count, pulse)
      _ <- ZIO.logInfo("datastar counter server on http://localhost:8080 (run Vite for the client)")
      _ <- Server
        .serve(routes(state))
        .provide(Server.defaultWith(_.port(8080).copy(responseCompression = Some(compression))))
    yield ()
end CounterServer
