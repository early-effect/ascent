package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.ElementPatchMode
import heddle.*
import heddle.datastar.{events, readSignals}
import heddle.json.zioJsonCodec
import zio.*
import zio.json.{JsonDecoder, JsonEncoder}
import zio.test.*

object AscentDatastarSpec extends ZIOSpecDefault:

  private def el(tag: String, children: Vector[UI[Any]] = Vector.empty) =
    UI.Element[Any](tag, Vector.empty, children)

  final case class Counter(count: Int) derives JsonEncoder, JsonDecoder
  given JsonCodec[Counter] = zioJsonCodec

  def spec = suite("AscentDatastar")(
    test("patch renders an ascent UI subtree into a datastar-patch-elements frame with selector + mode") {
      val sseHandler = events {
        AscentDatastar.patch(el("div", Vector(UI.Text("hi"))), "#cart", ElementPatchMode.Inner)
      }
      for
        response <- sseHandler.run(Request.get("/sse"))
        body     <- response.body.utf8
      yield assertTrue(
        response.status == Status.Ok,
        response.header(HeaderName.ContentType).exists(_.contains("text/event-stream")),
        body.contains("event: datastar-patch-elements"),
        body.contains("data: selector #cart"),
        body.contains("data: mode inner"),
        body.contains("data: elements <div"),
        body.contains("hi</div>"),
      )
      end for
    },
    test("patchRegion targets the region's #id with inner mode by default") {
      val sseHandler = events {
        AscentDatastar.patchRegion("cart", el("ul", Vector(UI.Text("items"))))
      }
      for
        response <- sseHandler.run(Request.get("/sse"))
        body     <- response.body.utf8
      yield assertTrue(
        body.contains("event: datastar-patch-elements"),
        body.contains("data: selector #cart"),
        body.contains("data: mode inner"),
        body.contains("items</ul>"),
      )
    },
    test("patchSignal emits a datastar-patch-signals frame with the named value") {
      val sseHandler = events {
        AscentDatastar.patchSignal("count", 42)
      }
      for
        response <- sseHandler.run(Request.get("/sse"))
        body     <- response.body.utf8
      yield assertTrue(
        body.contains("event: datastar-patch-signals"),
        body.contains("data: signals"),
        body.contains("\"count\":42"),
      )
    },
    test("the rendered HTML carries a data-ascent stamp (client/server id parity)") {
      val sseHandler = events {
        AscentDatastar.patch(el("section"), "#s", ElementPatchMode.Inner)
      }
      for
        response <- sseHandler.run(Request.get("/sse"))
        body     <- response.body.utf8
      yield assertTrue(body.contains("data-ascent="))
    },
    test("round-trip: a Request carrying the datastar signals our client posts decodes via readSignals") {
      val req = Request.post("/inc", Body.json("""{"count":7}"""))
      req.readSignals[Counter].map(decoded => assertTrue(decoded == Counter(7)))
    },
  )
end AscentDatastarSpec
