package ascent.docs

import ascent.*
import ascent.dom
import zio.*

/** Browser entry: mount each interactive example into its SSR `#<page-slug>-ex-N` wrapper. */
object ClientMain extends ZIOAppDefault:

  def run =
    val examples = ExampleRegistry.fromPages(
      GettingStarted.doc,
      SquawkPage.doc,
      Dsl.doc,
      ReactiveBoundaries.doc,
      Css.doc,
      ConduitPage.doc,
      Mounting.doc,
      HtmlPage.doc,
      DatastarPage.doc,
      Modules.doc,
    )
    for
      _ <- ZIO.foreachDiscard(examples.toList) { case (id, body) =>
        mountExample(id, body)
      }
      _ <- ZIO.never
    yield ()
  end run

  private def mountExample(id: String, body: URIO[Scope, ascent.ast.UI[Any]]): UIO[Unit] =
    val el = Dom.document.getElementById(id)
    if el == null then ZIO.unit
    else
      for
        _  <- ZIO.succeed(clearChildren(el))
        ui <- ZIO.scoped(body)
        _  <- AscentApp.mount(ui, el)
      yield ()
  end mountExample

  private def clearChildren(el: dom.Element): Unit =
    el.innerHTML = ""
end ClientMain
