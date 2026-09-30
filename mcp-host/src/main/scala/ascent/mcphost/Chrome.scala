package ascent.mcphost

import ascent.{dom, A, Aria, CssClass, Declaration, E, Ev, Lifecycle, Squawk, UI}
import ascent.dsl.*
import heddle.mcp.apps.host.{ConsentOutcome, ConsentRequest, Ending, LinkedView}
import zio.*
import zio.json.*

/** What an `<ascent-mcp-view>` draws around its view, in the element's shadow root: a border, a status line, the
  * question for a call, and the slot heddle frames the relay in. A page themes it with `--ascent-mcp-view-*` custom
  * properties, which reach into the shadow root.
  */
private[mcphost] object Chrome:
  object Box
      extends CssClass(
        Declaration("display", "block"),
        Declaration("border", "1px solid var(--ascent-mcp-view-border, #c8c8d0)"),
        Declaration("border-radius", "var(--ascent-mcp-view-radius, 8px)"),
        Declaration("overflow", "hidden"),
        Declaration("font", "var(--ascent-mcp-view-font, 13px system-ui, sans-serif)"),
      )

  object Status
      extends CssClass(
        Declaration("padding", "6px 10px"),
        Declaration("color", "var(--ascent-mcp-view-muted, #5a5a66)"),
      )

  /** The question sits above the frame, never over it: nothing the view draws is under the buttons. */
  object Question
      extends CssClass(
        Declaration("padding", "10px"),
        Declaration("border-top", "1px solid var(--ascent-mcp-view-border, #c8c8d0)"),
        Declaration("border-bottom", "1px solid var(--ascent-mcp-view-border, #c8c8d0)"),
        Declaration("background", "var(--ascent-mcp-view-question, #f6f6f9)"),
      )

  object Arguments
      extends CssClass(
        Declaration("margin", "6px 0"),
        Declaration("white-space", "pre-wrap"),
        Declaration("word-break", "break-all"),
      )

  object Choices extends CssClass(Declaration("display", "flex"), Declaration("gap", "8px"))

  object Slot extends CssClass(Declaration("display", "block"))

  /** The answers the question offers, and the button text for each. */
  private val choices: List[(ConsentOutcome, String)] = List(
    ConsentOutcome.AllowOnce       -> "Allow once",
    ConsentOutcome.AllowForSession -> "Allow for this session",
    ConsentOutcome.Rejected        -> "Don't allow",
  )

  def apply(
      view: LinkedView,
      states: Squawk[ViewState],
      prompt: Prompt,
      slot: Promise[Nothing, dom.HTMLDivElement],
  ): UI[Any] =
    val asking = states.map {
      case ViewState.Asking(request) => Some(request)
      case _                         => None
    }
    E.div(
      Box,
      E.div(Status, Aria.role("status"), Aria.ariaLive("polite"), states.map(status(view, _))),
      when(asking.map(_.isDefined))(question(asking, prompt)),
      E.div(Slot, Lifecycle.onMount[dom.HTMLDivElement](slot.succeed(_).unit)),
    )
  end apply

  private def question(asking: Squawk[Option[ConsentRequest]], prompt: Prompt): UI[Any] =
    E.div(
      Question,
      Aria.role("group"),
      Aria.ariaLabel("A call the view asks to make"),
      E.div(asking.map(_.fold("")(r => s"${r.server.value} asks to call ${r.tool.value}"))),
      E.pre(Arguments, asking.map(_.fold("")(_.arguments.toJsonPretty))),
      E.div(
        Choices,
        fragment(choices.map { (outcome, text) =>
          E.button(
            A.typ("button"),
            A.value(outcome.toString),
            A.disabled(prompt.armed.map(!_)),
            Ev.onClick(_ => prompt.answer(outcome)),
            text,
          )
        }*),
      ),
    )

  private def status(view: LinkedView, state: ViewState): String =
    val name = view.server.value
    state match
      case ViewState.Detached      => name
      case ViewState.Starting      => s"$name: starting"
      case ViewState.Serving       => name
      case ViewState.Asking(_)     => s"$name: waiting for your answer"
      case ViewState.Leaving       => s"$name: closing"
      case ViewState.Ended(ending) => s"$name: ended, ${ended(ending)}"
      case ViewState.Removed       => s"$name: closed"
      case ViewState.Failed(e)     => s"$name: did not start, ${e.message}"
  end status

  private def ended(ending: Ending): String = ending match
    case Ending.PortClosed       => "its connection closed"
    case Ending.Navigated        => "it navigated away"
    case Ending.TornDown(reason) => reason
end Chrome
