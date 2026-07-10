package example.chat

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*

/** The server-side render of the message list — an ascent `UI` value the server turns into HTML (via `ascent-html`) and
  * pushes into the client's `serverRegion("messages")`. Authored in the SAME typed DSL the client uses; the server is
  * "an ascent client" for this region.
  *
  * Display-only by design: the chat *interactions* (typing, sending) live in the client chrome, so the server region
  * stays purely server-owned with no client-side event wiring reaching into it.
  */
object MessageView:

  object Row
      extends CssClass(
        padding(10.px, 14.px),
        margin(6.px, Length.zero),
        borderRadius.px(10),
        background(Color.hex("#1b1e27")),
      )

  object Head
      extends CssClass(
        display.flex,
        justifyContent.spaceBetween,
        fontSize.px(12),
        color(Color.hex("#7cf6c8")),
        marginBottom.px(4),
      )

  object Body extends CssClass(color(Color.hex("#e6e8ef")), fontSize.px(15))

  object Empty
      extends CssClass(
        color(Color.hex("#5b6072")),
        fontStyle.italic,
        textAlign.center,
        padding(24.px, Length.zero),
      )

  private def formatTime(ts: Long): String =
    val s = (ts / 1000)    % 60
    val m = (ts / 60000)   % 60
    val h = (ts / 3600000) % 24
    f"$h%02d:$m%02d:$s%02d"

  private def row(msg: Message): UI[Any] =
    E.div(
      Row,
      E.div(Head, E.span(msg.username), E.span(formatTime(msg.timestamp))),
      E.div(Body, msg.content),
    )

  /** The whole message list as a single `UI` (an empty-state when there are none). */
  def list(messages: List[Message]): UI[Any] =
    if messages.isEmpty then E.div(Empty, "No messages yet — say hello!")
    else fragment(messages.map(row)*)
end MessageView
