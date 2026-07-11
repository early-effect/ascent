package ascent.docs

import ascent.*
import ascent.datastar.http.AscentDatastar
import ascent.dsl.*
import specular.{DocSpec, exampleZIO, md, page, section}
import zio.*
import zio.http.*
import zio.http.datastar.*
import zio.test.*

/** Client serverRegion + server patchRegion. */
object Hybrid extends DocSpec:

  def doc = page("Hybrid regions")(
    md"""
A **hybrid** UI keeps chrome on the client (inputs, layout, local Squawks) and owns a region on
the server. The client mounts `serverRegion("messages")` (an empty `#messages` container). The
server fills it with `AscentDatastar.patchRegion("messages", ui)`.

Docs pages have no live datastar server, so the client shape is shown as source only. For a
working chat, see `example/hybrid-chat`.
""",
    section("Client declaration")(
      md"""
```scala
E.div(
  E.h2("Chat"),
  serverRegion("messages"),
  E.input(A.placeholder("type…")),
)
```
"""
    ),
    section("Server patchRegion")(
      md"""
`patchRegion` targets `#id` with inner mode by default; the same id the client mounted.
""",
      exampleZIO {
        val list                                            = E.ul(E.li("hello"), E.li("world"))
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
        def pushMessages(msgs: Ref[Vector[String]]): ZIO[Datastar, Nothing, Unit] =
          msgs.get.flatMap { items =>
            AscentDatastar.patchRegion(
              "messages",
              E.ul(items.map(s => E.li(s))*),
              ascent.datastar.ElementPatchMode.Inner,
            )
          }

        def routes(msgs: Ref[Vector[String]], pulse: Hub[Unit]): Routes[Any, Nothing] =
          Routes(
            Method.GET / "sse" -> events {
              handler { (_: Request) =>
                for
                  _      <- pushMessages(msgs)
                  stream <- pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
                  _      <- stream.mapZIO(_ => pushMessages(msgs)).runDrain
                yield ()
              }
            },
            Method.POST / "send" -> handler { (_: Request) =>
              (msgs.update(_ :+ "ping") *> pulse.publish(()).unit).as(Response.ok)
            },
          ).sandbox

        (for
          pulse <- Hub.unbounded[Unit]
          msgs  <- Ref.make(Vector("welcome"))
          port  <- Server.install(routes(msgs, pulse))
          base = s"http://localhost:$port"
          st <- Client.batched(Request.post(s"$base/send", Body.empty)).map(_.status)
        yield st)
          .provideSomeLayer(Server.defaultWith(_.port(0)) ++ Client.default)
          .orDie
      }.assert(st => assertTrue(st == Status.Ok)),
    ),
  )
end Hybrid
