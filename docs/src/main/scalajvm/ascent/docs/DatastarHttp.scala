package ascent.docs

import ascent.datastar.http.AscentDatastar
import heddle.*
import heddle.datastar.{Datastar, events}
import specular.{DocSpec, exampleZIO, md, page, section}
import zio.*
import zio.json.JsonEncoder
import zio.test.*

/** Server bridge: AscentDatastar over heddle, with a Scope-bound test server. */
object DatastarHttp extends DocSpec:

  def doc = page("Datastar HTTP")(
    md"""
`ascent-datastar-http` makes a heddle server "an ascent client": render a `UI` with
`ascent-html`, push `patch-elements` / `patch-signals` through heddle's Datastar SSE generator.
""",
    section("patchSignal")(
      md"""
Handler-level assert: the SSE body carries a `datastar-patch-signals` frame with the named value.
""",
      exampleZIO {
        val sse = events {
          AscentDatastar.patchSignal("count", 42)
        }
        (for
          response <- sse.run(Request.get("/sse"))
          body     <- response.body.utf8
        yield (
          response.status == Status.Ok,
          body.contains("event: datastar-patch-signals"),
          body.contains("\"count\":42"),
        )).orDie
      }.assert { case (ok, ev, signal) => assertTrue(ok, ev, signal) },
    ),
    section("Live counter server")(
      md"""
End-to-end against an in-process server (ephemeral port, scoped to the example): `POST /increment`
succeeds while the server is live, then the Scope closes and the port is released.
""",
      exampleZIO {
        case class State(count: Ref[Int], pulse: Hub[Unit])

        def pushCount(state: State): ZIO[Datastar, Nothing, Unit] =
          state.count.get.flatMap(c => AscentDatastar.patchSignal("count", c)(using JsonEncoder.int))

        def routes(state: State): Routes[Any, Nothing] =
          Routes(
            Method.GET / "sse" -> events {
              for
                _      <- pushCount(state)
                stream <- state.pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
                _      <- stream.mapZIO(_ => pushCount(state)).runDrain
              yield ()
            },
            Method.POST / "increment" -> handler { (_: Request) =>
              (state.count.update(_ + 1) *> state.pulse.publish(()).unit).as(Response.ok)
            },
          )

        (for
          count <- Ref.make(0)
          pulse <- Hub.unbounded[Unit]
          state = State(count, pulse)
          result <- ZIO
            .scoped {
              Server
                .install(routes(state))
                .mapError(e => RuntimeException(e.message))
                .flatMap { server =>
                  server.port.flatMap { port =>
                    Client.request(Method.POST, s"http://127.0.0.1:$port/increment").map(_.status)
                  }
                }
            }
            .provide(Server.defaultWith(_.port(0)))
        yield result).orDie
      }.assert(st => assertTrue(st == Status.Ok)),
    ),
  )
end DatastarHttp
