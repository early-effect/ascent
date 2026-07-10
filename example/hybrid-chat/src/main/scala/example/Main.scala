package example

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*
import ascent.datastar.SignalStore
import ascent.datastar.js.{Action, DatastarClient}
import zio.*

/** A HYBRID chat: the chrome — layout, inputs, send button, typing indicator — is normal client-side ascent (reactive
  * `Squawk`s, two-way-bound inputs, ascent event handlers). Only the MESSAGE LIST is server-driven: a
  * `serverRegion("messages")` the server fills by rendering ascent `UI` to HTML and pushing it via `patchRegion`. The
  * typing indicator rides the signal channel.
  *
  *   - `username` / `message` are WRITABLE signals (two-way bound to the inputs); the server reads them from each
  *     action's posted signals.
  *   - `typing` is a read-only signal the server pushes.
  *   - the message list is opaque server-owned HTML inside the region — ascent never reconciles it.
  */
object Main extends ZIOAppDefault:

  object Page
      extends GlobalStyle(
        Selector(Elem.body, margin.zero, background(Color.hex("#0b0d12"))),
        Selector(Sel.universal, fontFamily.of(FontFamily.systemUi, FontFamily.sansSerif), boxSizing.borderBox),
      )

  object Shell
      extends CssClass(
        maxWidth.px(560),
        margin(40.px, Length.auto),
        padding(24.px),
        borderRadius.px(16),
        background(Color.hex("#11131a")),
        color(Color.hex("#e6e8ef")),
        boxShadow(Shadow(Length.zero, 20.px, 60.px, Color.rgba(0, 0, 0, 0.45))),
      )

  object Title extends CssClass(margin(Length.zero, Length.zero, 4.px, Length.zero), color(Color.hex("#7cf6c8")))
  object Sub
      extends CssClass(
        margin(Length.zero, Length.zero, 16.px, Length.zero),
        color(Color.hex("#7b8194")),
        fontSize.px(13),
      )

  object Messages
      extends CssClass(
        minHeight.px(220),
        maxHeight.px(360),
        overflowY.auto,
        padding(8.px),
        borderRadius.px(12),
        background(Color.hex("#0e1017")),
        marginBottom.px(12),
      )

  object Typing extends CssClass(minHeight.px(18), fontSize.px(12), color(Color.hex("#7b8194")), marginBottom.px(8))

  object Field
      extends CssClass(
        width.pct(100),
        padding(12.px, 14.px),
        marginBottom.px(8),
        border(Border.solid(1.px, Color.hex("#262a36"))),
        borderRadius.px(10),
        background(Color.hex("#0e1017")),
        color(Color.hex("#e6e8ef")),
        fontSize.px(15),
        Selector(PseudoClass.focus, outline.none, borderColor(Color.hex("#7cf6c8"))),
      )

  object Send
      extends CssClass(
        width.pct(100),
        padding(12.px),
        border.none,
        borderRadius.px(10),
        cursor.pointer,
        fontWeight(600),
        background(Color.hex("#7cf6c8")),
        color(Color.hex("#0b0d12")),
        Selector(PseudoClass.hover, background(Color.hex("#9affd8"))),
      )

  def view(store: SignalStore) =
    for
      username <- store.source("username", "")
      message  <- store.source("message", "")
      typing   <- store.squawk("typing", "")
    yield E.body(
      Page,
      scoped {
        DatastarClient.connect("/chat/sse", store).as {
          E.div(
            Shell,
            E.h1(Title, "ascent ⇄ datastar — hybrid chat"),
            E.p(Sub, "Chrome is client ascent. The message list is a server-driven region."),
            // SERVER-DRIVEN: the server renders & patches messages into this region. The region is
            // its own `<div id="messages">`; we wrap it in a styled scroll viewport.
            E.div(Messages, serverRegion("messages")),
            // CLIENT: typing indicator fed by the `typing` signal the server pushes.
            E.div(Typing, typing),
            // CLIENT: two-way-bound inputs; values are read server-side from the posted signals.
            E.input(
              Field,
              A.typ("text"),
              A.placeholder("Your name"),
              A.value(username),
              Events.onInput(e => username.set(e.targetValue.getOrElse(""))),
            ),
            E.input(
              Field,
              A.typ("text"),
              A.placeholder("Type a message and press Enter"),
              A.value(message),
              Events.onInput(e => message.set(e.targetValue.getOrElse("")) *> Action.post(store, "/chat/typing")),
              Events.onKeyDown(e => if e.key.contains("Enter") then send(store, message) else ZIO.unit),
            ),
            E.button(Send, "Send", Ev.onClick(_ => send(store, message))),
          )
        }
      },
    )

  /** Post the send action, then clear the message input locally. */
  private def send(store: SignalStore, message: ascent.squawk.Source[String]) =
    Action.post(store, "/chat/send") *> message.set("")

  def run =
    for
      store <- SignalStore.make()
      ui    <- view(store)
      _     <- AscentApp.mountBody(ui)
      _     <- ZIO.never
    yield ()
end Main
