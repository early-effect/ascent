package example.mcphost.view

import ascent.{dom, sq, A, CssClass, Declaration, E, Ev, Source, Squawk}
import ascent.dsl.*
import ascent.mcpapp.{AppInfo, McpApp}
import example.mcphost.{Count, Counter}
import heddle.mcp.apps.ui.{PostMessageBridge, Run}
import zio.*

/** The counter's view: an ascent MCP App that shows the count and asks the host to call `inc`. It runs inside the
  * host's sandbox, so all it can reach is its host.
  */
object Main extends ZIOAppDefault:
  object Row
      extends CssClass(
        Declaration("display", "flex"),
        Declaration("gap", "12px"),
        Declaration("align-items", "center"),
        Declaration("padding", "12px"),
        Declaration("font", "15px system-ui, sans-serif"),
      )

  /** The count the host last answered: `show_counter`'s, until the user presses +1. */
  private def view(pressed: Source[Option[Count]]) = McpApp(Counter.shed).view { (run, bridge) =>
    val shown = Squawk.zipWith(run, pressed) {
      case (_, Some(count))               => s"count ${count.value}"
      case (Run.Returned(_, count), None) => s"count ${count.value}"
      case (Run.Failed(_, _), None)       => "the call failed"
      case _                              => "waiting for the host"
    }
    E.div(
      Row,
      E.output(shown),
      E.button(
        A.typ("button"),
        Ev.onClick(_ => bridge.call(_.inc)(()).flatMap(count => pressed.set(Some(count))).ignore),
        "+1",
      ),
    )
  }

  def run =
    for
      body    <- ZIO.succeed(dom.document.body).someOrElseZIO(ZIO.dieMessage("a view's document always has a body"))
      pressed <- sq(Option.empty[Count])
      _       <- view(pressed).mount(PostMessageBridge.toParent, body, AppInfo("counter-view", "1"))
      _       <- ZIO.never
    yield ()
end Main
