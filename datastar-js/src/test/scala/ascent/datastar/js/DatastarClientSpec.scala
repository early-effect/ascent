package ascent.datastar.js

import ascent.ast.UI
import ascent.datastar.{Datastar, SignalStore}
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js
import ascent.js.AscentApp

/** Client-runtime behaviour under jsdom. jsdom's `EventSource` can't connect to a server, so tests drive routing
  * directly via `DatastarClient.applyEvent` (the effect the SSE callback runs) — the same path a frame takes, sans
  * socket.
  */
object DatastarClientSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use)

  private def patchSignals(json: String) =
    Datastar.parse(Datastar.PatchSignals, s"signals $json").toOption.get

  private def patchElements(data: String) =
    Datastar.parse(Datastar.PatchElements, data).toOption.get

  def spec = suite("DatastarClient")(
    suite("patch-signals → Squawk")(
      test("a signals frame drives a bound ReactiveText repaint via Mount") {
        withParent { parent =>
          for
            store <- SignalStore.make()
            count <- store.squawk("count", 0)
            ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(count.map(_.toString))))
            _ <- AscentApp.mount(ui, parent)
            span   = parent.asInstanceOf[js.Dynamic].firstChild
            before = span.textContent.asInstanceOf[String]
            _ <- DatastarClient.applyEvent(patchSignals("""{"count":5}"""), store)
            after = span.textContent.asInstanceOf[String]
          yield assertTrue(before == "0", after == "5")
        }
      },
      test("a malformed frame is dropped without disturbing the store") {
        for
          store <- SignalStore.make()
          count <- store.squawk("count", 1)
          // parse fails, so the listener's Left branch never calls applyEvent: the store stays untouched.
          parsed = Datastar.parse(Datastar.PatchSignals, "signals {nope")
          now <- count.get
        yield assertTrue(parsed.isLeft, now == 1)
      },
    ),
    suite("patch-elements → DOM")(
      test("inner replaces the target's children") {
        withParent { parent =>
          for
            store <- SignalStore.make()
            _     <- ZIO.succeed {
              parent.asInstanceOf[js.Dynamic].id = "cart"
              parent.asInstanceOf[js.Dynamic].innerHTML = "<span>old</span>"
            }
            _ <- DatastarClient.applyEvent(patchElements("selector #cart\nmode inner\nelements <b>new</b>"), store)
          yield assertTrue(parent.asInstanceOf[js.Dynamic].innerHTML.asInstanceOf[String] == "<b>new</b>")
        }
      },
      test("append adds to the end of the target's children") {
        withParent { parent =>
          for
            store <- SignalStore.make()
            _     <- ZIO.succeed {
              parent.asInstanceOf[js.Dynamic].id = "feed"
              parent.asInstanceOf[js.Dynamic].innerHTML = "<li>a</li>"
            }
            _ <- DatastarClient.applyEvent(patchElements("selector #feed\nmode append\nelements <li>b</li>"), store)
          yield assertTrue(parent.asInstanceOf[js.Dynamic].innerHTML.asInstanceOf[String] == "<li>a</li><li>b</li>")
        }
      },
      test("remove deletes the targeted element") {
        withParent { parent =>
          for
            store <- SignalStore.make()
            _     <- ZIO.succeed {
              val child = dom.document.createElement("div")
              child.setAttribute("id", "gone")
              parent.appendChild(child)
            }
            present = dom.document.querySelector("#gone") != null
            _ <- DatastarClient.applyEvent(patchElements("selector #gone\nmode remove"), store)
            absent = dom.document.querySelector("#gone") == null
          yield assertTrue(present, absent)
        }
      },
      test("a selector matching nothing is a no-op (no crash)") {
        for
          store <- SignalStore.make()
          _     <- DatastarClient.applyEvent(patchElements("selector #missing\nmode inner\nelements <b>x</b>"), store)
        yield assertCompletes
      },
    ),
  )
end DatastarClientSpec
