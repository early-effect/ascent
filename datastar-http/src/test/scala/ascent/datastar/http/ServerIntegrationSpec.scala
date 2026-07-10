package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.{Datastar as DsDialect, ElementPatchMode}
import zio.*
import zio.http.*
import zio.http.datastar.*
import zio.test.*

/** End-to-end through the real zio-http routing + SDK stack, closing the loop route → `Html.render` → SDK patch → SSE
  * bytes → ascent client parse. The route runs via `routes.runZIO` in-process, not against a bound socket, to avoid a
  * real socket's flakiness while still exercising the actual `events`/SSE encoding.
  */
object ServerIntegrationSpec extends ZIOSpecDefault:

  private def el(tag: String, children: Vector[UI[Any]]) =
    UI.Element[Any](tag, Vector.empty, children)

  private val program: ZIO[Datastar, Nothing, Unit] =
    AscentDatastar.patch(el("div", Vector(UI.Text("hello"))), "#app", ElementPatchMode.Inner) *>
      AscentDatastar.patchSignal("count", 1)

  private val sseHandler: Handler[Any, Nothing, Any, Response] = events(handler(program))
  private val routes: Routes[Any, Nothing]                     = Routes(Method.GET / "sse" -> sseHandler)

  /** Split a raw SSE body into the (eventName, dataBlock) frames a browser hands the client, one per blank-line block.
    */
  private def frames(raw: String): Vector[(String, String)] =
    raw
      .split("\n\n")
      .iterator
      .filter(_.trim.nonEmpty)
      .map { block =>
        val lines = block.linesIterator.toVector
        val event =
          lines.collectFirst { case l if l.startsWith("event:") => l.stripPrefix("event:").trim }.getOrElse("")
        val data = lines
          .collect { case l if l.startsWith("data:") => l.stripPrefix("data:").stripPrefix(" ") }
          .mkString("\n")
        event -> data
      }
      .toVector

  def spec = suite("ServerIntegration")(
    test("the full route+SDK stack emits frames our client dialect decodes") {
      for
        response <- routes.runZIO(Request.get(url"/sse"))
        raw      <- response.body.asString
      yield
        val parsed = frames(raw).map((ev, data) => DsDialect.parse(ev, data))
        assertTrue(
          response.status == Status.Ok,
          response.headers.get(Header.ContentType).exists(_.renderedValue.contains("text/event-stream")),
          raw.contains("event: datastar-patch-elements"),
          raw.contains("event: datastar-patch-signals"),
          parsed.forall(_.isRight),
          parsed.exists {
            case Right(ascent.datastar.RemoteEvent.PatchElements(html, Some("#app"), ElementPatchMode.Inner, _)) =>
              html.contains("hello")
            case _ => false
          },
          parsed.exists {
            case Right(ascent.datastar.RemoteEvent.PatchSignals(_, _)) => true
            case _                                                     => false
          },
        )
    } @@ TestAspect.timeout(30.seconds)
  ) @@ TestAspect.sequential
end ServerIntegrationSpec
