package ascent.docs

import ascent.*
import ascent.datastar.http.AscentDatastar
import specular.{DocSpec, exampleZIO, md, page, section}
import zio.*
import zio.http.{Body, Client, Handler, Request, Status}
import zio.http.datastar.{Datastar, events}
import zio.test.*

/** Server bridge: AscentDatastar over zio-http, with a Scope-bound test server. */
object DatastarHttp extends DocSpec:

  def doc = page("Datastar HTTP")(
    md"""
`ascent-datastar-http` makes a zio-http server "an ascent client": render a `UI` with
`ascent-html`, push `patch-elements` / `patch-signals` through the official datastar SDK.
Keep the SDK's `events { handler { … } }` idiom; swap templates for ascent views.
""",
    section("patchSignal")(
      md"""
Handler-level assert: the SSE body carries a `datastar-patch-signals` frame with the named value.
""",
      exampleZIO {
        val handler0: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
          AscentDatastar.patchSignal("count", 42)
        }
        val sse = events(handler0)
        for
          response <- sse(())
          body     <- response.body.asString.orDie
        yield (
          response.status == Status.Ok,
          body.contains("event: datastar-patch-signals"),
          body.contains("\"count\":42"),
        )
      }.assert { case (ok, ev, signal) => assertTrue(ok, ev, signal) },
    ),
    section("Live counter server")(
      md"""
End-to-end against an in-process server (ephemeral port, scoped to the example): `POST /increment`
succeeds while the server is live, then the Scope closes and the port is released.
""",
      exampleZIO {
        DocServers
          .withCounter { base =>
            Client.batched(Request.post(s"$base/increment", Body.empty)).map(_.status)
          }
          .provideSomeLayer(Client.default)
          .orDie
      }.assert(st => assertTrue(st == Status.Ok)),
    ),
  )
end DatastarHttp
