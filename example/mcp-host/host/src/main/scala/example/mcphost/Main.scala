package example.mcphost

import ascent.{dom, sq, A, AscentApp, CssClass, Declaration, DevReload, E, Ev, Lifecycle, Source, UI}
import ascent.dsl.*
import ascent.mcphost.{McpView, McpViews}
import heddle.{Api, Client}
import heddle.error.HeddleError
import heddle.mcp.Mcp
import heddle.mcp.apps.{withApp, HostPolicy, Origin, UiDocument}
import heddle.mcp.apps.frame.RelayMode
import heddle.mcp.apps.host.*
import heddle.mcp.client.McpClient
import heddle.mcp.protocol.{Implementation, ToolName}
import zio.*
import zio.json.ast.Json

/** The page could not load the view bundle it serves. */
final case class NoViewBundle(reason: String)

/** A host page for MCP Apps in one file. A counter server runs in this page, reached in memory; each press of "Show a
  * counter" is the model calling `show_counter`, and its view is framed in an `<ascent-mcp-view>`. The audit below
  * follows every decision the host makes.
  */
object Main extends ZIOAppDefault:
  private val info = Implementation("ascent-mcp-host-demo", "1")

  object Page
      extends CssClass(
        Declaration("margin", "0 auto"),
        Declaration("max-width", "720px"),
        Declaration("padding", "24px"),
        Declaration("font", "15px system-ui, sans-serif"),
        Declaration("display", "grid"),
        Declaration("gap", "16px"),
      )

  object Card extends CssClass(Declaration("display", "grid"), Declaration("gap", "8px"))

  object Log
      extends CssClass(
        Declaration("font", "12px ui-monospace, monospace"),
        Declaration("white-space", "pre-wrap"),
        Declaration("background", "#f6f6f9"),
        Declaration("padding", "8px"),
        Declaration("margin", "0"),
      )

  /** One framed view, and the scope it lives in. */
  final case class Shown(id: Int, view: McpView, scope: Scope.Closeable)

  /** The view's linked bundle, which the preview stages next to this page. `fetch` is the impure edge; its failure
    * becomes the page's own.
    */
  private val viewScript: IO[NoViewBundle, String] =
    ZIO
      .fromPromiseJS(dom.window.fetch("counter-view.js"))
      .mapError(e => NoViewBundle(e.getMessage))
      .filterOrFail(_.ok)(NoViewBundle("counter-view.js is not staged next to this page"))
      .flatMap(response => ZIO.fromPromiseJS(response.text()).mapError(e => NoViewBundle(e.getMessage)))

  /** The counter server, in this page, serving `script` as its view. */
  private def server(script: String) =
    for
      n <- Ref.make(0)
      api = Api("Counter", "1.0.0")
        .job(Counter.show)(_ => n.get.map(Count(_)))
        .resource(Counter.inc)(_ => n.updateAndGet(_ + 1).map(Count(_)))
      mcp     <- ZIO.fromEither(Mcp.from(api))
      app     <- ZIO.fromEither(mcp.withApp(Counter.shed, UiDocument("Counter", script)))
      session <- McpClient
        .http("http://counter.local/mcp", McpClient.Settings(info))
        .provideSome[Scope](Client.inMemory(app.routes))
    yield AppServer(ServerName("counter"), session)

  /** The page's views: what it has framed, and how it frames the next. */
  final class Launcher(
      views: McpViews,
      host: AppsHost,
      counter: AppServer,
      next: Ref[Int],
      val shown: Source[Seq[Shown]],
  ):
    /** What the model's `show_counter` call hands the host, framed in a new element with a scope of its own. */
    val launch: ZIO[HashPins & Audit & ConsentMemory, HeddleError, Unit] =
      for
        result <- counter.session.callTool(ToolName("show_counter"), Json.Obj())
        mount  <- host.mount(counter, Launched(ToolName("show_counter"), Json.Obj(), result))
        scope  <- Scope.make
        view <- views.frame(mount, RelayMode.Opaque).provideSomeEnvironment[ConsentMemory & Audit](_.add[Scope](scope))
        id   <- next.getAndUpdate(_ + 1)
        _    <- shown.update(_ :+ Shown(id, view, scope))
      yield ()

    /** Asks the view to go, then takes its element out and closes its scope. */
    def close(s: Shown): UIO[Unit] =
      s.view.teardown("closed from the page") *> shown.update(_.filterNot(_.id == s.id)) *> s.scope.close(Exit.unit)
  end Launcher

  /** Puts a view's element in the page once its slot is. */
  private def slot(view: McpView): UI[Any] =
    E.div(Lifecycle.onMount[dom.HTMLDivElement](d => ZIO.succeed(d.appendChild(view.element)).unit))

  private def line(e: AuditEvent): String =
    val tool = e.action match
      case Action.Request(method, tool, _) => s"$method${tool.fold("")(t => s" ${t.value}")}"
      case other                           => other.toString
    s"#${e.generation.value} $tool: ${e.decision}"

  private def page(
      launcher: Launcher,
      problem: Source[Option[String]],
      log: Source[Vector[String]],
  ): UI[HashPins & Audit & ConsentMemory] =
    E.body(
      Page,
      E.h1("<ascent-mcp-view>"),
      E.p(
        "Each press is the model calling show_counter. The host frames the counter's view in its own element, and " +
          "asks you before the view's +1 reaches the server."
      ),
      E.div(
        E.button(
          A.typ("button"),
          Ev.onClick(_ => (problem.set(None) *> launcher.launch).catchAll(e => problem.set(Some(e.message)))),
          "Show a counter",
        )
      ),
      E.p(problem.map(_.getOrElse(""))),
      forEach(launcher.shown)(_.id.toString)(s =>
        E.section(Card, slot(s.view), E.button(A.typ("button"), Ev.onClick(_ => launcher.close(s)), "Close"))
      ),
      E.h2("Audit"),
      E.pre(Log, log.map(_.mkString("\n"))),
    )

  def run =
    ZIO
      .scoped(
        for
          script  <- viewScript
          counter <- server(script)
          host    <- AppsHost.make(HostSettings(info, HostPolicy.open))
          origin  <- ZIO.fromEither(Origin.from(dom.window.location.origin))
          views   <- McpViews.define(origin)
          shown   <- sq(Seq.empty[Shown])
          problem <- sq(Option.empty[String])
          log     <- sq(Vector.empty[String])
          next    <- Ref.make(1)
          events  <- ZIO.serviceWithZIO[Audit](_.events)
          _       <- events.foreach(e => log.update(_ :+ line(e))).forkScoped
          _       <- AscentApp.mountBody(page(Launcher(views, host, counter, next, shown), problem, log))
          _       <- ZIO.succeed(DevReload.install())
          _       <- ZIO.never
        yield ()
      )
      .provide(HashPins.inMemory, Audit.layer(), ConsentMemory.layer)
end Main
