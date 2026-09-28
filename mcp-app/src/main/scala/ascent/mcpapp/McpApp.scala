package ascent.mcpapp

import ascent.ast.UI
import ascent.dom
import ascent.js.{AscentApp, Subscriptions}
import ascent.squawk.{Squawk, sq}
import heddle.mcp.apps.{Grant, Shed}
import heddle.mcp.apps.ui.*
import heddle.mcp.client.{McpError, McpSession}
import scala.NamedTuple.NamedTuple
import zio.*
import zio.stream.ZStream

/** An MCP App view in ascent. `McpApp(shed)` pins every type from the shed, so `view`'s lambda needs no ascription:
  *
  * {{{
  * val app = McpApp(counter).view { (run, bridge) =>
  *   E.div(
  *     E.output(run.map { case Run.Returned(_, count) => count.value.toString; case _ => "…" }),
  *     E.button(Ev.onClick(_ => bridge.call(_.inc)(()).ignoreLogged), "+"),
  *   )
  * }
  * }}}
  */
final class McpApp[In, Err, Out, N <: Tuple, V <: Tuple] private (
    val shed: Shed[?, N, V],
    connect: (ViewPort, AppBridge.Settings) => ZIO[Scope, McpError, AppBridge[In, Err, Out, N, V]],
    render: (Squawk[Run[In, Err, Out]], ViewBridge[In, Err, Out, N, V]) => UI[Any],
):
  /** Connects over `port`, keeps the run and host context as squawks, and mounts the view into `parent`. The view lives
    * until the scope closes; the host's `ui/resource-teardown` unmounts it and interrupts its calls first.
    */
  def mount(port: ViewPort, parent: dom.Element, app: AppInfo): ZIO[Scope, McpError, Mounted[In, Err, Out]] =
    for
      views   <- Supervised.make
      done    <- Promise.make[Nothing, Unit]
      mounted <- Promise.make[Nothing, Subscriptions]
      // Subscriptions release the view's listeners and observers; its nodes go with the container's content.
      unmount = mounted.await.flatMap(_.cancelAll) *> ZIO.succeed(parent.textContent = None) *>
        views.interruptAll *> done.succeed(()).unit
      bridge  <- connect(port, app.settings(onTeardown = unmount))
      run     <- sq(Run.waiting: Run[In, Err, Out])
      context <- sq(bridge.host.hostContext)
      _       <- bridge.run.foreach(run.set).forkScoped
      _       <- bridge.context.foreach(context.set).forkScoped
      _       <- Resize.report(parent, bridge)
      view = ViewBridge(bridge, views)
      subs <- AscentApp.mount(McpApp.themed(context, render(run, view)), parent)
      _    <- mounted.succeed(subs)
      _    <- ZIO.addFinalizer(unmount)
    yield Mounted(run, context, done.await)
end McpApp

object McpApp:
  /** Pins the shed's launch types through its launch grant; `view` then takes the render function. */
  def apply[L <: Grant[?, ?, ?], N <: Tuple, V <: Tuple, In, Err, Out](shed: Shed[L, N, V])(using
      launch: L <:< Grant[In, Err, Out]
  ): Builder[In, Err, Out, N, V] =
    Builder(shed, (port, settings) => AppBridge.connect(shed, port, settings))

  final class Builder[In, Err, Out, N <: Tuple, V <: Tuple] private[McpApp] (
      shed: Shed[?, N, V],
      connect: (ViewPort, AppBridge.Settings) => ZIO[Scope, McpError, AppBridge[In, Err, Out, N, V]],
  ):
    def view(
        render: (Squawk[Run[In, Err, Out]], ViewBridge[In, Err, Out, N, V]) => UI[Any]
    ): McpApp[In, Err, Out, N, V] =
      new McpApp(shed, connect, render)

  /** The view, under a `<style>` that sets each standard theme variable the host sends as a custom property on `:root`,
    * so ascent-css reads `var(--color-background-primary)` and the rest.
    */
  private def themed(context: Squawk[HostContext], view: UI[Any]): UI[Any] =
    import ascent.*
    import ascent.dsl.*
    E.div(E.style(context.map(themeRule)), view)

  private[mcpapp] def themeRule(context: HostContext): String =
    val decls = context.styles.toList.flatMap(_.variables.toList).sortBy(_._1.ordinal).map { (v, value) =>
      s"${v.wire}: ${value.filterNot(c => c == '{' || c == '}' || c == ';')};"
    }
    if decls.isEmpty then "" else decls.mkString(":root { ", " ", " }")
end McpApp

/** What a view is, as it introduces itself at `ui/initialize`. */
final case class AppInfo(name: String, version: String, capabilities: AppCapabilities = AppCapabilities()):
  private[mcpapp] def settings(onTeardown: UIO[Unit]): AppBridge.Settings =
    AppBridge.Settings(heddle.mcp.protocol.Implementation(name, version), capabilities, onTeardown = onTeardown)

/** A mounted view: its run and host context, and `closed`, which completes when the host has torn it down. */
final case class Mounted[In, Err, Out](run: Squawk[Run[In, Err, Out]], context: Squawk[HostContext], closed: UIO[Unit])

/** The bridge a view renders with. Each call runs as a fiber the view owns, so the host's teardown interrupts it. */
final class ViewBridge[In, Err, Out, N <: Tuple, V <: Tuple] private[mcpapp] (
    val bridge: AppBridge[In, Err, Out, N, V],
    views: Supervised,
):
  def call[I, E, O](pick: NamedTuple[N, V] => Grant[I, E, O]): ViewBridge.Call[I, E, O] =
    ViewBridge.Call(bridge.call(pick), views)

  def openLink(url: String): IO[McpError, Outcome]                     = views.own(bridge.openLink(url))
  def requestDisplayMode(mode: DisplayMode): IO[McpError, DisplayMode] = views.own(bridge.requestDisplayMode(mode))
  def requestTeardown: IO[McpError, Unit]                              = bridge.requestTeardown
end ViewBridge

object ViewBridge:
  final class Call[I, E, O] private[mcpapp] (call: McpSession.CallPartiallyApplied[I, E, O], views: Supervised):
    def apply(in: I): IO[heddle.mcp.client.McpCallFailure[E], O] = views.own(call(in))

/** The fibers a view's handlers start. Ascent runs a handler on the runtime, outside any scope, so the view keeps its
  * own set to interrupt at teardown.
  */
private[mcpapp] final class Supervised private (live: Ref[Map[FiberId, Fiber[Any, Any]]]):
  def own[E, A](effect: IO[E, A]): IO[E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(effect).fork.flatMap { f =>
        live.update(_ + (f.id -> f)) *> restore(f.join).ensuring(live.update(_ - f.id))
      }
    }

  val interruptAll: UIO[Unit] = live.get.flatMap(fs => ZIO.foreachParDiscard(fs.values)(_.interrupt))

private[mcpapp] object Supervised:
  val make: UIO[Supervised] = Ref.make(Map.empty[FiberId, Fiber[Any, Any]]).map(Supervised(_))

/** A scoped `ResizeObserver` on the view's container: each size it reports goes to the host as `size-changed`. The
  * observer's callback only hands the size to a stream's emitter, which is how a browser callback enters ZIO.
  */
private object Resize:
  def report(target: dom.Element, bridge: AppBridge[?, ?, ?, ?, ?]): URIO[Scope, Unit] =
    sizes(target).foreach((w, h) => bridge.sizeChanged(Some(w), Some(h)).ignore).forkScoped.unit

  private def sizes(target: dom.Element): ZStream[Any, Nothing, (Double, Double)] =
    ZStream.asyncScoped[Any, Nothing, (Double, Double)] { emit =>
      val observer = new dom.ResizeObserver((entries, _) =>
        entries.lastOption match
          case Some(e) =>
            val _ = emit(ZIO.succeed(Chunk.single((e.contentRect.width, e.contentRect.height))))
          case None => ()
      )
      ZIO.acquireRelease(ZIO.succeed(observer.observe(target)))(_ => ZIO.succeed(observer.disconnect()))
    }
end Resize
