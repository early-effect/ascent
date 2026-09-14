package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.{Datastar as DsDialect, ElementPatchMode}
import heddle.*
import heddle.datastar.{Datastar, events}
import zio.*
import zio.test.*

object ServerIntegrationSpec extends ZIOSpecDefault:

  private def el(tag: String, children: Vector[UI[Any]]) =
    UI.Element[Any](tag, Vector.empty, children)

  private val program: ZIO[Datastar, Nothing, Unit] =
    AscentDatastar.patch(el("div", Vector(UI.Text("hello"))), "#app", ElementPatchMode.Inner) *>
      AscentDatastar.patchSignal("count", 1)

  private val routes: Routes[Any, Nothing] =
    Routes(Method.GET / "sse" -> events(program))

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
        response <- routes.runZIO(Request.get("/sse"))
        raw      <- response.body.utf8
      yield
        val parsed = frames(raw).map((ev, data) => DsDialect.parse(ev, data))
        assertTrue(
          response.status == Status.Ok,
          response.header(HeaderName.ContentType).exists(_.contains("text/event-stream")),
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
