package ascent.js

import ascent.*
import ascent.ast.{Attr, UI}
import ascent.dom
import ascent.domtypes.Events
import ascent.dsl.*
import zio.*
import zio.test.*

import scala.scalajs.js

/** Scope-native DOM listener binding ([[Lifecycle.onMountScoped]] + [[Dom.listen]] + [[Dom.onDocument]]): a listener
  * attached on mount is removed automatically on unmount, with no paired OnUnmount or hand-stashed function reference.
  */
object DomListenSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  /** Dispatch a synthetic `keydown` with the given key at `document`. */
  private def dispatchKeydown(key: String): UIO[Unit] =
    dispatchKeydownAt(dom.document.asInstanceOf[js.Dynamic], key)

  private def dispatchKeydownAt(target: js.Dynamic, key: String): UIO[Unit] =
    ZIO.succeed {
      val evt = js.Dynamic.global.KeyboardEvent
      val e   = js.Dynamic.newInstance(evt)("keydown", js.Dynamic.literal(key = key, bubbles = true))
      target.dispatchEvent(e)
      ()
    }

  def spec = suite("Dom.listen + OnMountScoped")(
    test("OnMountScoped finalizer runs once on cleanup, not on mount") {
      withParent { parent =>
        for
          released <- Ref.make(0)
          ui: UI[Any] = UI.Element(
            "div",
            Vector(
              Attr.OnMountScoped[Any](_ => ZIO.addFinalizer(released.update(_ + 1)).unit)
            ),
            Vector.empty,
          )
          cleanup     <- AscentApp.mount(ui, parent)
          duringMount <- released.get
          _           <- cleanup.cancelAll
          afterClean  <- released.get
        yield assertTrue(duringMount == 0, afterClean == 1)
      }
    },
    test("a document listener bound via Dom.listen fires while mounted") {
      withParent { parent =>
        for
          hits <- Ref.make(0)
          ui: UI[Any] = E.div(
            Lifecycle.onMountScoped[dom.Element, Any] { _ =>
              Dom.listen(Dom.document, Events.onKeyDown)(_ => hits.update(_ + 1))
            }
          )
          _    <- AscentApp.mount(ui, parent)
          _    <- dispatchKeydown("/")
          seen <- hits.get
        yield assertTrue(seen == 1)
      }
    },
    test("the document listener is REMOVED on cleanup — no leak after unmount") {
      withParent { parent =>
        for
          hits <- Ref.make(0)
          ui: UI[Any] = E.div(
            Lifecycle.onMountScoped[dom.Element, Any] { _ =>
              Dom.listen(Dom.document, Events.onKeyDown)(_ => hits.update(_ + 1))
            }
          )
          cleanup      <- AscentApp.mount(ui, parent)
          _            <- dispatchKeydown("a")
          whileMounted <- hits.get
          _            <- cleanup.cancelAll
          _            <- dispatchKeydown("b")
          afterClean   <- hits.get
        yield assertTrue(whileMounted == 1, afterClean == 1)
      }
    },
    test("Dom.onDocument hands the typed element + event to the handler and auto-removes") {
      withParent { parent =>
        for
          keys <- Ref.make(Vector.empty[String])
          ui: UI[Any] = E.input(
            Dom.onDocument[dom.HTMLInputElement, Any](Events.onKeyDown) { (el, ev) =>
              keys.update(_ :+ s"${el.tagName.toLowerCase}:${ev.key.getOrElse("?")}")
            }
          )
          cleanup    <- AscentApp.mount(ui, parent)
          _          <- dispatchKeydown("/")
          mounted    <- keys.get
          _          <- cleanup.cancelAll
          _          <- dispatchKeydown("/")
          afterClean <- keys.get
        yield assertTrue(mounted == Vector("input:/"), afterClean == Vector("input:/"))
      }
    },
    test("Dom.onWindow binds a window-level listener that fires while mounted and auto-removes") {
      withParent { parent =>
        for
          hits <- Ref.make(0)
          ui: UI[Any] = E.input(
            Dom.onWindow[dom.HTMLInputElement, Any](Events.onKeyDown)((_, _) => hits.update(_ + 1))
          )
          cleanup      <- AscentApp.mount(ui, parent)
          _            <- dispatchKeydownAt(dom.window.asInstanceOf[js.Dynamic], "/")
          whileMounted <- hits.get
          _            <- cleanup.cancelAll
          _            <- dispatchKeydownAt(dom.window.asInstanceOf[js.Dynamic], "/")
          afterClean   <- hits.get
        yield assertTrue(whileMounted == 1, afterClean == 1)
      }
    },
    test("targetTag exposes the event target's tag name without touching the raw event") {
      withParent { parent =>
        for
          tags <- Ref.make(Vector.empty[String])
          ui: UI[Any] = E.input(
            Dom.onDocument[dom.HTMLInputElement, Any](Events.onKeyDown) { (_, ev) =>
              tags.update(_ :+ ev.targetTag.getOrElse("none"))
            }
          )
          _   <- AscentApp.mount(ui, parent)
          _   <- dispatchKeydown("x")
          got <- tags.get
        yield assertTrue(got.nonEmpty)
      }
    },
  )
end DomListenSpec
