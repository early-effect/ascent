package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.ElementPatchMode
import zio.*
import zio.http.*
import zio.http.datastar.*
import zio.schema.derived
import zio.test.*

/** Drives the AscentDatastar wrapper through the SDK's `events{}` and inspects the SSE response: `Html.render` output
  * must land in a well-formed `datastar-patch-elements` frame, and `readSignals` must decode what the client posts.
  */
object AscentDatastarSpec extends ZIOSpecDefault:

  private def el(tag: String, children: Vector[UI[Any]] = Vector.empty) =
    UI.Element[Any](tag, Vector.empty, children)

  final case class Counter(count: Int) derives zio.schema.Schema

  def spec = suite("AscentDatastar")(
    test("patch renders an ascent UI subtree into a datastar-patch-elements frame with selector + mode") {
      val handler: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
        AscentDatastar.patch(el("div", Vector(UI.Text("hi"))), "#cart", ElementPatchMode.Inner)
      }
      val sseHandler = events(handler)
      for
        response <- sseHandler(())
        body     <- response.body.asString
      yield assertTrue(
        response.status == Status.Ok,
        response.headers.get(Header.ContentType).exists(_.renderedValue.contains("text/event-stream")),
        body.contains("event: datastar-patch-elements"),
        body.contains("data: selector #cart"),
        body.contains("data: mode inner"),
        body.contains("data: elements <div"),
        body.contains("hi</div>"),
      )
      end for
    },
    test("patchRegion targets the region's #id with inner mode by default") {
      val handler: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
        AscentDatastar.patchRegion("cart", el("ul", Vector(UI.Text("items"))))
      }
      val sseHandler = events(handler)
      for
        response <- sseHandler(())
        body     <- response.body.asString
      yield assertTrue(
        body.contains("event: datastar-patch-elements"),
        body.contains("data: selector #cart"),
        body.contains("data: mode inner"),
        body.contains("items</ul>"),
      )
      end for
    },
    test("patchSignal emits a datastar-patch-signals frame with the named value") {
      val handler: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
        AscentDatastar.patchSignal("count", 42)
      }
      val sseHandler = events(handler)
      for
        response <- sseHandler(())
        body     <- response.body.asString
      yield assertTrue(
        body.contains("event: datastar-patch-signals"),
        body.contains("data: signals"),
        body.contains("\"count\":42"),
      )
    },
    test("the rendered HTML carries a data-ascent stamp (client/server id parity)") {
      val handler: Handler[Datastar, Nothing, Any, Unit] = Handler.fromZIO {
        AscentDatastar.patch(el("section"), "#s", ElementPatchMode.Inner)
      }
      val sseHandler = events(handler)
      for
        response <- sseHandler(())
        body     <- response.body.asString
      yield assertTrue(body.contains("data-ascent="))
    },
    test("round-trip: a Request carrying the datastar signals our client posts decodes via readSignals") {
      val signalsBody = """{"count":7}""" // the body shape Action.post sends
      val req         = Request.post(url"/inc", Body.fromString(signalsBody))
      for decoded <- req.readSignals[Counter]
      yield assertTrue(decoded == Counter(7))
    },
  )
end AscentDatastarSpec
