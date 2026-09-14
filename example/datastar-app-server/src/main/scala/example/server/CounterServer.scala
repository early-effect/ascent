package example.server

import ascent.datastar.http.AscentDatastar
import ascent.preview.{Preview, PreviewConfig}
import heddle.*
import heddle.brotli.Brotli
import heddle.datastar.{Datastar, events}
import zio.*

import java.nio.file.Path as JPath

/** The heddle backend for the datastar counter example. */
object CounterServer extends ZIOAppDefault:

  final case class State(count: Ref[Int], pulse: Hub[Unit])

  def makeState: UIO[State] =
    for
      count <- Ref.make(0)
      pulse <- Hub.unbounded[Unit]
    yield State(count, pulse)

  private def bump(state: State): UIO[Unit] =
    state.count.update(_ + 1) *> state.pulse.publish(()).unit

  private def pushCount(state: State): ZIO[Datastar, Nothing, Unit] =
    state.count.get.flatMap(c => AscentDatastar.patchSignal("count", c))

  def apiRoutes(state: State): Routes[Any, Nothing] =
    Routes(
      Method.GET / "sse" -> events {
        for
          _      <- pushCount(state)
          stream <- state.pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
          _      <- stream.mapZIO(_ => pushCount(state)).runDrain
        yield ()
      },
      Method.POST / "increment" -> handler { (_: Request) =>
        bump(state).as(Response.ok)
      },
    )

  def routes(state: State, previewRoot: JPath, port: Int = 8080): Routes[Any, Response] =
    apiRoutes(state) ++ Preview.routes(PreviewConfig(root = previewRoot, port = port))

  def resolvePreviewRoot(args: Chunk[String]): JPath =
    args.headOption
      .map(JPath.of(_))
      .getOrElse(JPath.of("example/datastar-app/target/preview"))
      .toAbsolutePath
      .normalize

  def run =
    for
      args <- getArgs
      root = resolvePreviewRoot(args)
      state <- makeState
      _     <- ZIO.logInfo(s"datastar counter on http://localhost:8080 serving $root")
      _     <- Preview
        .serve(PreviewConfig(root = root, port = 8080), extraRoutes = apiRoutes(state))
        .provideSome[Scope](
          Server.defaultWith(_.port(8080).copy(compressors = Chunk(Compressor.gzip, Brotli.compressor)))
        )
    yield ()
end CounterServer
