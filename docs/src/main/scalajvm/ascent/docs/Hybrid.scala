package ascent.docs

import ascent.*
import ascent.datastar.http.AscentDatastar
import ascent.dsl.*
import specular.{DocSpec, example, exampleZIO, md, page, section}
import zio.*
import zio.http.{Body, Client, Handler, Request, Status}
import zio.http.datastar.{Datastar, events}
import zio.test.*

/** Client serverRegion + server patchRegion. */
object Hybrid extends DocSpec:

  def doc = page("Hybrid regions")(
    md"""
A **hybrid** UI keeps chrome on the client (inputs, layout, local Squawks) and owns a region on
the server. The client mounts `serverRegion("messages")` (an empty `#messages` container). The
server fills it with `AscentDatastar.patchRegion("messages", ui)`.
""",
    section("Client declaration")(
      example {
        E.div(
          E.h2("Chat"),
          serverRegion("messages"),
          E.input(A.placeholder("type…")),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Server patchRegion")(
      md"""
`patchRegion` targets `#id` with inner mode by default; the same id the client mounted.
""",
      exampleZIO {
        val list = E.ul(E.li("hello"), E.li("world"))
        val handler0: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
          AscentDatastar.patchRegion("messages", list)
        }
        val sse = events(handler0)
        for
          response <- sse(())
          body     <- response.body.asString.orDie
        yield (
          body.contains("event: datastar-patch-elements"),
          body.contains("data: selector #messages"),
          body.contains("hello"),
        )
      }.assert { case (ev, sel, html) => assertTrue(ev, sel, html) },
    ),
    section("Scoped live server")(
      md"""
Same Scope-bound pattern as the counter: start routes, `POST` a message, confirm OK. Full chat
wiring lives in `example/hybrid-chat`.
""",
      exampleZIO {
        DocServers
          .withHybridRegion { base =>
            Client.batched(Request.post(s"$base/send", Body.empty)).map(_.status)
          }
          .provideSomeLayer(Client.default)
          .orDie
      }.assert(st => assertTrue(st == Status.Ok)),
    ),
  )
end Hybrid
