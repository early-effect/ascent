package example.server

import ascent.datastar.http.AscentDatastar
import ascent.preview.{Preview, PreviewConfig}
import zio.*
import zio.http.*
import zio.http.datastar.*

import java.nio.file.Path as JPath

/** The zio-http backend for the datastar counter example — "the server is an ascent client."
  *
  *   - holds the count in a `Ref`, with a `Hub` that pulses on every change;
  *   - `GET /sse` opens a datastar SSE stream: it pushes the current count immediately, then a fresh `patch-signals` on
  *     every subsequent change (the chat-example pattern);
  *   - `POST /increment` reads nothing from the body — it just bumps the count and pulses the hub;
  *   - response compression (brotli/gzip) is enabled via zio-http's own `Server.Config`;
  *   - [[Preview.routes]] are composed in so the spliced client (`example/datastar-app/target/preview`) is same-origin
  *     on `:8080`.
  *
  * The client (a pure-ascent Scala.js app) turns each pushed signal into a `Squawk` and lets ascent's Mount engine
  * repaint — no datastar.js.
  */
object CounterServer extends ZIOAppDefault:

  final case class State(count: Ref[Int], pulse: Hub[Unit])

  def makeState: UIO[State] =
    for
      count <- Ref.make(0)
      pulse <- Hub.unbounded[Unit]
    yield State(count, pulse)

  private def bump(state: State): UIO[Unit] =
    state.count.update(_ + 1) *> state.pulse.publish(()).unit

  /** Push the current count as a `patch-signals` frame. */
  private def pushCount(state: State): ZIO[Datastar, Nothing, Unit] =
    state.count.get.flatMap(c => AscentDatastar.patchSignal("count", c))

  def apiRoutes(state: State): Routes[Any, Nothing] =
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

  /** Preview (static + SSE reload) composed with the counter API. */
  def routes(state: State, previewRoot: JPath, port: Int = 8080): Routes[Any, Response] =
    Preview.routes(PreviewConfig(root = previewRoot, port = port)) ++ apiRoutes(state)

  def resolvePreviewRoot(args: Chunk[String]): JPath =
    args.headOption
      .map(JPath.of(_))
      .getOrElse(JPath.of("example/datastar-app/target/preview"))
      .toAbsolutePath
      .normalize

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
      args <- getArgs
      root = resolvePreviewRoot(args)
      state <- makeState
      _     <- ZIO.logInfo(s"datastar counter on http://localhost:8080 serving $root")
      _     <- Server
        .serve(routes(state, root))
        .provide(Server.defaultWith(_.port(8080).copy(responseCompression = Some(compression))))
    yield ()
end CounterServer
