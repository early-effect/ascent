package example

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*
import ascent.datastar.SignalStore
import ascent.datastar.js.{Action, DatastarClient}
import zio.*

/** A server-driven counter that proves the full datastar loop with ascent owning the client AST.
  *
  *   - The SERVER holds the count and streams `datastar-patch-signals` over SSE.
  *   - The CLIENT (this app) is PURE ascent: a [[SignalStore]] turns each incoming signal into a `Squawk`, and ascent's
  *     own Mount engine repaints the bound text — no datastar.js, no innerHTML.
  *   - The increment button POSTs an action; the server bumps the count and pushes a new signal, which flows straight
  *     back into the same `Squawk`.
  *
  * `scaffolded` via [[AscentApp.mountBody]]; the SSE connection is opened in a `scoped` boundary so it ties to the page
  * lifetime (and would tear down with the subtree).
  */
object Main extends ZIOAppDefault:

  object Card
      extends CssClass(
        maxWidth.px(420),
        margin(80.px, Length.auto),
        padding(48.px),
        borderRadius.px(16),
        background(Color.hex("#11131a")),
        color(Color.hex("#e6e8ef")),
        fontFamily.of(FontFamily.systemUi, FontFamily.sansSerif),
        textAlign.center,
        boxShadow(Shadow(Length.zero, 20.px, 60.px, Color.rgba(0, 0, 0, 0.45))),
      )

  object Count
      extends CssClass(
        fontSize.px(96),
        fontWeight(700),
        lineHeight(1),
        margin(16.px, Length.zero),
        color(Color.hex("#7cf6c8")),
      )

  object Button
      extends CssClass(
        fontSize.px(18),
        fontWeight(600),
        padding(14.px, 28.px),
        border.none,
        borderRadius.px(10),
        cursor.pointer,
        background(Color.hex("#7cf6c8")),
        color(Color.hex("#0b0d12")),
        Selector(PseudoClass.hover, background(Color.hex("#9affd8"))),
      )

  def view(store: SignalStore) =
    for count <- store.squawk("count", 0)
    yield E.body(
      // Open the SSE connection for the page's lifetime; its body renders the visible card.
      scoped {
        DatastarClient.connect("/sse", store).as {
          E.div(
            Card,
            E.h1("ascent ⇄ datastar"),
            E.p("The server owns the count. ascent renders it."),
            E.div(Count, count.map(_.toString)),
            E.button(
              Button,
              "increment",
              Ev.onClick(_ => Action.post(store, "/increment")),
            ),
          )
        }
      }
    )

  def run =
    for
      store <- SignalStore.make()
      ui    <- view(store)
      _     <- AscentApp.mountBody(ui)
      // Keep the app fiber alive for the page's lifetime.
      _ <- ZIO.never
    yield ()
end Main
