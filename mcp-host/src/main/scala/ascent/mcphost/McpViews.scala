package ascent.mcphost

import ascent.{dom, AscentApp, Source, Squawk, sq}
import ascent.element.{CustomElement, DefineError, ElementLifecycle, ElementName}
import heddle.mcp.apps.Origin
import heddle.mcp.apps.frame.{Frame, FrameError, RelayMode}
import heddle.mcp.apps.host.{Audit, ConsentGate, ConsentMemory, Ending, Mount, Mounted}
import zio.*

/** One `<ascent-mcp-view>`. Put `element` in the page to frame its view; call `teardown` to end it the way MCP Apps
  * asks of a host, and take the element out after. Taking it out first ends the view unasked, since its frame goes with
  * it.
  */
final class McpView private[mcphost] (
    val element: dom.HTMLElement,
    states: Source[ViewState],
    prompt: Prompt,
    served: Ref[Option[Mounted]],
    running: Ref[Option[Fiber[Nothing, Unit]]],
):
  /** What the view is doing now, and each change after. */
  def state: Squawk[ViewState] = states

  /** The first state `p` accepts: the one the view is in now, or the first it changes to. */
  def until(p: ViewState => Boolean): UIO[ViewState] =
    Promise
      .make[Nothing, ViewState]
      .flatMap: reached =>
        def offer(s: ViewState): UIO[Unit] = reached.succeed(s).when(p(s)).unit
        ZIO.scoped(ZIO.acquireRelease(states.observe(offer))(_.cancel) *> states.get.flatMap(offer) *> reached.await)

  /** How the view ended, once it has. */
  def ended: UIO[ViewState] = until(_.over)

  /** Ends the view. One that is up has its question withdrawn, then is sent `ui/resource-teardown` with `reason` and
    * given the host's `teardownWait` to save what it must, its frame still in the page. One whose element the page has
    * already taken out has no frame left to ask, and ends as removed. One not up yet has nothing to save, and stops.
    * Answers how it ended.
    */
  def teardown(reason: String): UIO[ViewState] =
    states.get.flatMap {
      case ViewState.Serving | ViewState.Asking(_) =>
        ZIO.ifZIO(ZIO.succeed(element.isConnected))(
          states.set(ViewState.Leaving) *> prompt.withdraw *>
            served.get.flatMap(ZIO.foreachDiscard(_)(_.teardown(reason))),
          stop,
        ) *> ended
      case ViewState.Detached | ViewState.Starting =>
        halt *> states.set(ViewState.Ended(Ending.TornDown(reason))) *> ended
      case ViewState.Leaving | ViewState.Ended(_) | ViewState.Removed | ViewState.Failed(_) => ended
    }

  /** Frames the view with `open`, on a fiber in `in`, if it has never been framed. */
  private[mcphost] def start(open: ZIO[Scope, FrameError, Mounted], in: Scope): UIO[Unit] =
    ZIO.whenZIODiscard(states.get.map(_ == ViewState.Detached))(
      states.set(ViewState.Starting) *> serve(open).forkIn(in).flatMap(f => running.set(Some(f)))
    )

  private val halt: UIO[Unit] = running.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(_.interrupt))

  /** The page took the element out, and the frame is already gone with it. */
  private[mcphost] val stop: UIO[Unit] =
    prompt.withdraw *> halt *> states.update(s => if s.over then s else ViewState.Removed)

  /** Serves the view until its mount ends. The frame lives in the inner scope, so the view is shown as ended only once
    * its frame has gone.
    */
  private def serve(open: ZIO[Scope, FrameError, Mounted]): UIO[Unit] =
    ZIO
      .scoped(
        open.foldZIO(
          e => ZIO.succeed(ViewState.Failed(e)),
          mounted =>
            served.set(Some(mounted)) *>
              states.update(s => if s == ViewState.Starting then ViewState.Serving else s) *>
              mounted.ending.map(ViewState.Ended(_)),
        )
      )
      .flatMap(end => prompt.withdraw *> states.set(end))

end McpView

/** The page's `<ascent-mcp-view>` elements. Each frames one MCP App view in heddle's relay, with a border, the view's
  * state, and the question for each call it asks to make, drawn in the element's shadow root. The shadow root is the
  * page's own origin and realm: the chrome is packaging, and heddle's double iframe is what isolates the view.
  *
  * Each element is its own frame's consent gate, so the question for a call always shows on the frame whose view asked.
  * What the user allows for the session lives in the page's one [[ConsentMemory]], so it holds for every element.
  */
final class McpViews private (element: CustomElement, host: Origin, armAfter: Duration):

  /** An element that frames `mount` through `relay` once the page puts it in a document. A mount is served once, so an
    * element put back after its view ended shows how it ended. When the scope closes, a view still up is torn down.
    */
  def frame(mount: Mount, relay: RelayMode): URIO[Scope & ConsentMemory & Audit, McpView] =
    for
      el      <- element.create
      states  <- sq[ViewState](ViewState.Detached)
      prompt  <- Prompt.make(states, armAfter)
      slot    <- Promise.make[Nothing, dom.HTMLDivElement]
      chrome  <- ZIO.succeed(McpViews.shadowContainer(el))
      _       <- ZIO.acquireRelease(AscentApp.mount(Chrome(mount.view, states, prompt, slot), chrome))(_.cancelAll)
      gate    <- ZIO.serviceWith[ConsentMemory](_.gate(prompt.ask))
      audit   <- ZIO.service[Audit]
      serving <- ZIO.serviceWithZIO[Scope](_.fork)
      served  <- Ref.make(Option.empty[Mounted])
      running <- Ref.make(Option.empty[Fiber[Nothing, Unit]])
      view = McpView(el, states, prompt, served, running)
      open = slot.await
        .flatMap(Frame.mount(mount, relay, _, host))
        .provideSomeEnvironment[Scope](_ ++ ZEnvironment[ConsentGate, Audit](gate, audit))
      lifecycle <- element.of(el)
      _         <- lifecycle.foreach {
        case ElementLifecycle.Connected(_)    => view.start(open, serving)
        case ElementLifecycle.Disconnected(_) => view.stop
      }.forkScoped
      // Last in, so it runs first: the view is asked to go while its frame is still in the page.
      _ <- ZIO.addFinalizer(view.teardown(McpViews.closed))
    yield view
end McpViews

object McpViews:
  val name: ElementName = ElementName("ascent-mcp-view")

  /** Why a view still up is torn down when the page's scope for it closes. */
  val closed: String = "the page closed the view"

  /** Defines `<ascent-mcp-view>` for the page, which a page can do once. `host` is this page's origin, the only one a
    * relay talks to. `armAfter` is how long a question's buttons stay disabled after it appears, so a click aimed at
    * the view cannot land on "Allow"; one second, as browsers hold their own prompts.
    */
  def define(host: Origin, armAfter: Duration = 1.second): ZIO[Scope, DefineError, McpViews] =
    CustomElement.define(name).map(McpViews(_, host, armAfter))

  /** An open shadow root holding one container for the chrome. Open, so a page's tests can read it; the page is trusted
    * either way, and the view is in its own iframe.
    */
  private def shadowContainer(el: dom.HTMLElement): dom.HTMLDivElement =
    val root = el.attachShadow(new dom.ShadowRootInit:
      mode = "open")
    val container = dom.document.createElement(dom.HtmlTag.div)
    val _         = root.appendChild(container)
    container
end McpViews
