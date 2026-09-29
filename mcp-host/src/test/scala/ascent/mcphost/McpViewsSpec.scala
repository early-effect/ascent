package ascent.mcphost

import ascent.dom
import heddle.{/, Api, Client, Endpoint, Schema}
import heddle.mcp.Mcp
import heddle.mcp.apps.{withApp, Grant, HostPolicy, Origin, Shed, UiDocument, UiUri}
import heddle.mcp.apps.frame.RelayMode
import heddle.mcp.apps.host.*
import heddle.mcp.client.McpClient
import heddle.mcp.protocol.{Implementation, ToolName}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.ZStream
import zio.test.*

final case class Count(value: Int) derives Schema, JsonCodec

/** `<ascent-mcp-view>` in a real browser, around a real heddle server reached in memory: the element frames its view
  * when the page puts it in, asks the user about each call on its own frame, and ends the mount when the page tears it
  * down or takes it out. The buttons arm on the test clock; everything else waits on what the page and the audit say.
  */
object McpViewsSpec extends ZIOSpecDefault:
  private val show = Endpoint.get("counter").out[Count].name("show_counter")
  private val inc  = Endpoint.post("counter" / "inc").out[Count].name("inc")
  private val shed = Shed(UiUri("ui://counter/view"), "Counter", Grant.launch(show))((inc = Grant.app(inc)))

  /** A view that does the handshake, asks once to call `inc`, and answers teardown. Plain JavaScript, as any server's
    * view could be.
    */
  private val view = UiDocument(
    "Counter",
    """let next = 0;
      |const pending = {};
      |window.addEventListener("message", e => {
      |  const m = e.data;
      |  if (e.source !== parent || !m) return;
      |  if (m.method === "ui/resource-teardown") parent.postMessage({ jsonrpc: "2.0", id: m.id, result: {} }, "*");
      |  else if (m.id !== undefined && pending[m.id]) { pending[m.id](m); delete pending[m.id]; }
      |});
      |const ask = (method, params) => new Promise(resolve => {
      |  const id = ++next;
      |  pending[id] = resolve;
      |  parent.postMessage({ jsonrpc: "2.0", id, method, params }, "*");
      |});
      |(async () => {
      |  await ask("ui/initialize", { appInfo: { name: "counter", version: "1" }, appCapabilities: {}, protocolVersion: "2026-01-26" });
      |  parent.postMessage({ jsonrpc: "2.0", method: "ui/notifications/initialized", params: {} }, "*");
      |  await ask("tools/call", { name: "inc", arguments: {} });
      |})();""".stripMargin,
  )

  private val counter  = ServerName("counter")
  private val armAfter = 1.second

  /** The page defines the element once, so every test shares it. */
  private val views = ZLayer.scoped(
    ZIO.fromEither(Origin.from(dom.window.location.origin)).flatMap(McpViews.define(_, armAfter))
  )

  /** A counter server, a session to it in memory, and each count its `inc` reaches, as it reaches it. */
  private val server =
    for
      n    <- Ref.make(0)
      incs <- Queue.unbounded[Int]
      api = Api("Counter", "1.0.0")
        .job(show)(_ => n.get.map(Count(_)))
        .resource(inc)(_ => n.updateAndGet(_ + 1).tap(incs.offer).map(Count(_)))
      mcp     <- ZIO.fromEither(Mcp.from(api))
      app     <- ZIO.fromEither(mcp.withApp(shed, view))
      session <- McpClient
        .http("http://counter.test/mcp", McpClient.Settings(Implementation("mcp-host-spec", "1")))
        .provideSome[Scope](Client.inMemory(app.routes))
    yield (AppServer(counter, session), incs)

  private val appsHost = AppsHost.make(HostSettings(Implementation("mcp-host-spec", "1"), HostPolicy.open))

  /** A fresh mount of the counter's view, as a host makes one after the model calls `show_counter`. */
  private def mountOf(host: AppsHost, server: AppServer) =
    server.session
      .callTool(ToolName("show_counter"), Json.Obj())
      .map(Launched(ToolName("show_counter"), Json.Obj(), _))
      .flatMap(host.mount(server, _))

  private def attach(view: McpView): UIO[Unit] = ZIO.succeed(dom.document.body.foreach(_.appendChild(view.element)))

  private def detach(view: McpView): UIO[Unit] = ZIO.succeed(view.element.remove())

  private def asking(s: ViewState): Boolean = s match
    case ViewState.Asking(_) => true
    case _                   => false

  private def shadow(view: McpView, selector: String): UIO[Option[dom.Element]] =
    ZIO.succeed(view.element.shadowRoot.flatMap(_.querySelector(selector)))

  /** The question's button for `outcome`, in the element's shadow root. */
  private def button(view: McpView, outcome: ConsentOutcome): UIO[Option[dom.HTMLButtonElement]] =
    shadow(view, s"""button[value="$outcome"]""").map(_.collect { case b: dom.HTMLButtonElement => b })

  private def press(view: McpView, outcome: ConsentOutcome): UIO[Unit] =
    button(view, outcome).flatMap(b => ZIO.succeed(b.foreach(_.click())))

  private def armed(view: McpView, outcome: ConsentOutcome): UIO[Boolean] =
    button(view, outcome).map(_.exists(!_.disabled))

  private def framed(view: McpView): UIO[Boolean] = shadow(view, "iframe").map(_.isDefined)

  /** The audit's decisions about `tools/call` from now on, in order. */
  private val calls: URIO[Scope & Audit, ZStream[Any, Nothing, Decision]] =
    ZIO
      .serviceWithZIO[Audit](_.events)
      .map(_.collect { case AuditEvent(_, _, _, _, Action.Request("tools/call", _, _), d) => d })

  /** Frames a second element on the page and waits until its view asks. The page hands out lifecycle callbacks in
    * order, so by then it has handled whatever the page did to an element before this.
    */
  private def afterward(views: McpViews, host: AppsHost, server: AppServer) =
    for
      mount <- mountOf(host, server)
      probe <- views.frame(mount, RelayMode.Opaque)
      _     <- attach(probe)
      _     <- probe.until(asking)
      _     <- detach(probe)
    yield ()

  private val framing = suite("framing")(
    test("the element frames its view once the page puts it in, and the call waits on buttons that arm on time"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          decisions <- calls
          host      <- appsHost
          (s, incs) <- server
          mount     <- mountOf(host, s)
          view      <- views.frame(mount, RelayMode.Opaque)
          before    <- view.state.get
          _         <- attach(view)
          question  <- view.until(asking)
          _         <- TestClock.adjust(armAfter.minusMillis(1))
          early     <- armed(view, ConsentOutcome.AllowOnce)
          _         <- TestClock.adjust(1.milli)
          late      <- armed(view, ConsentOutcome.AllowOnce)
          _         <- press(view, ConsentOutcome.AllowOnce)
          n         <- incs.take
          log       <- decisions.take(2).runCollect
          after     <- view.state.get
        yield assertTrue(
          before == ViewState.Detached,
          question match
            case ViewState.Asking(r) => r.server == counter && r.tool == ToolName("inc")
            case _                   => false
          ,
          !early,
          late,
          n == 1,
          log == Chunk(Decision.ConsentAsked(ConsentOutcome.AllowOnce), Decision.Allowed),
          after == ViewState.Serving,
        )
      )
    ,
    test("a click before the buttons arm is no answer, and Don't allow keeps the call from the server"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          decisions <- calls
          host      <- appsHost
          (s, incs) <- server
          mount     <- mountOf(host, s)
          view      <- views.frame(mount, RelayMode.Opaque)
          _         <- attach(view)
          _         <- view.until(asking)
          _         <- press(view, ConsentOutcome.AllowOnce)
          still     <- view.state.get
          _         <- TestClock.adjust(armAfter)
          _         <- press(view, ConsentOutcome.Rejected)
          log       <- decisions.take(2).runCollect
          after     <- view.until(_ == ViewState.Serving)
          n         <- incs.size
        yield assertTrue(
          asking(still),
          log == Chunk(
            Decision.ConsentAsked(ConsentOutcome.Rejected),
            Decision.Denied(Denial.ConsentRefused(ConsentOutcome.Rejected)),
          ),
          after == ViewState.Serving,
          n == 0,
        )
      )
    ,
    test("what the user allows for the session on one element holds for every element on the page"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          decisions <- calls
          host      <- appsHost
          (s, incs) <- server
          first     <- mountOf(host, s)
          second    <- mountOf(host, s)
          a         <- views.frame(first, RelayMode.Opaque)
          b         <- views.frame(second, RelayMode.Opaque)
          _         <- attach(a)
          _         <- a.until(asking)
          _         <- TestClock.adjust(armAfter)
          _         <- press(a, ConsentOutcome.AllowForSession)
          one       <- incs.take
          _         <- attach(b)
          two       <- incs.take
          log       <- decisions.take(4).runCollect
          bState    <- b.state.get
          aQuestion <- shadow(a, "button").map(_.isDefined)
        yield assertTrue(
          one == 1,
          two == 2,
          log == Chunk(
            Decision.ConsentAsked(ConsentOutcome.AllowForSession),
            Decision.Allowed,
            Decision.ConsentAsked(ConsentOutcome.AllowForSession),
            Decision.Allowed,
          ),
          bState == ViewState.Serving,
          !aQuestion,
        )
      ),
  )

  private val ending = suite("ending")(
    test("teardown withdraws the question, asks the view to go, and ends the mount with the page's reason"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          decisions <- calls
          host      <- appsHost
          (s, incs) <- server
          mount     <- mountOf(host, s)
          view      <- views.frame(mount, RelayMode.Opaque)
          _         <- attach(view)
          _         <- view.until(asking)
          // The clock never moves, so the host's patience never runs out: this ends only if the view answers.
          ended    <- view.teardown("the user closed the thread")
          log      <- decisions.take(2).runCollect
          question <- shadow(view, "button").map(_.isDefined)
          frame    <- framed(view)
          n        <- incs.size
        yield assertTrue(
          ended == ViewState.Ended(Ending.TornDown("the user closed the thread")),
          log == Chunk(
            Decision.ConsentAsked(ConsentOutcome.Cancelled),
            Decision.Denied(Denial.ConsentRefused(ConsentOutcome.Cancelled)),
          ),
          !question,
          !frame,
          n == 0,
        )
      )
    ,
    test("a view torn down before the page puts it in is never framed"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          host      <- appsHost
          (s, incs) <- server
          mount     <- mountOf(host, s)
          view      <- views.frame(mount, RelayMode.Opaque)
          ended     <- view.teardown("not needed")
          _         <- attach(view)
          _         <- afterward(views, host, s)
          after     <- view.state.get
          frame     <- framed(view)
          n         <- incs.size
        yield assertTrue(
          ended == ViewState.Ended(Ending.TornDown("not needed")),
          after == ended,
          !frame,
          n == 0,
        )
      )
    ,
    test("taking the element out ends the mount and withdraws its question, and putting it back does not frame again"):
      ZIO.scoped(
        for
          views     <- ZIO.service[McpViews]
          host      <- appsHost
          (s, incs) <- server
          mount     <- mountOf(host, s)
          view      <- views.frame(mount, RelayMode.Opaque)
          _         <- attach(view)
          _         <- view.until(asking)
          _         <- detach(view)
          removed   <- view.ended
          question  <- shadow(view, "button").map(_.isDefined)
          _         <- attach(view)
          _         <- afterward(views, host, s)
          again     <- view.state.get
          frame     <- framed(view)
          _         <- detach(view)
          n         <- incs.size
        yield assertTrue(removed == ViewState.Removed, !question, again == ViewState.Removed, !frame, n == 0)
      ),
  )

  // Each body is scoped, so its elements close, and tear their views down, while the test's layers are still open.
  def spec = suite("<ascent-mcp-view>")(framing, ending)
    .provideSome[McpViews](HashPins.inMemory, Audit.layer(), ConsentMemory.layer)
    .provideShared(views)
    @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
end McpViewsSpec
