package ascent.js

import ascent.ast.UI
import ascent.dom
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** Dynamic reactive boundaries (ReactiveChild, When): slot-based content tracking with no anchor comments, plus the
  * cascading-cleanup invariant — a swap cancels the OLD subtree's observers before wiring the new ones.
  */
object MountDynamicSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  /** Count visible (non-comment) children that have a concrete tag/text. */
  private def visibleChildCount(parent: dom.Node): Int =
    val cs = parent.asInstanceOf[js.Dynamic].childNodes
    var n  = 0
    var i  = 0
    while i < cs.length.asInstanceOf[Int] do
      val nt = cs.item(i).nodeType.asInstanceOf[Int]
      if nt != 8 then n += 1 // not COMMENT_NODE
      i += 1
    n

  def spec = suite("Mount (dynamic)")(
    suite("ReactiveChild")(
      test("emits the initial content with NO anchor comments in the DOM") {
        withParent { parent =>
          for
            content <- sq[UI[Any]](UI.Element("span", Vector.empty, Vector(UI.Text("first"))))
            ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.ReactiveChild(content)))
            _ <- AscentApp.mount(ui, parent)
          yield
            val divEl = parent.asInstanceOf[js.Dynamic].firstChild
            val nodes = divEl.childNodes
            assertTrue(
              nodes.length.asInstanceOf[Int] == 1,
              nodes.item(0).nodeType.asInstanceOf[Int] != 8,
              nodes.item(0).textContent.asInstanceOf[String] == "first",
            )
        }
      },
      test("setting the Squawk replaces the content; the parent contains exactly the new content") {
        withParent { parent =>
          for
            content <- sq[UI[Any]](UI.Element("span", Vector.empty, Vector(UI.Text("first"))))
            ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.ReactiveChild(content)))
            _ <- AscentApp.mount(ui, parent)
            divEl = parent.asInstanceOf[js.Dynamic].firstChild
            _ <- content.set(UI.Element("p", Vector.empty, Vector(UI.Text("second"))))
          yield
            val nodes = divEl.childNodes
            assertTrue(
              nodes.length.asInstanceOf[Int] == 1,
              nodes.item(0).tagName.asInstanceOf[String].toLowerCase == "p",
              nodes.item(0).textContent.asInstanceOf[String] == "second",
            )
        }
      },
      test("swapping cancels the OLD subtree's reactive observers (no leaks)") {
        withParent { parent =>
          for
            inner   <- sq("alpha")
            content <- sq[UI[Any]](UI.Element("span", Vector.empty, Vector(UI.ReactiveText(inner))))
            ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.ReactiveChild(content)))
            _  <- AscentApp.mount(ui, parent)
            n0 <- inner.observerCount
            _  <- content.set(UI.Text("brand new"))
            n1 <- inner.observerCount
          yield assertTrue(n0 == 1, n1 == 0)
        }
      },
      test("Mount cleanup also runs the active subtree's cleanup, cancelling its observers") {
        withParent { parent =>
          for
            inner   <- sq("only")
            content <- sq[UI[Any]](UI.Element("span", Vector.empty, Vector(UI.ReactiveText(inner))))
            ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.ReactiveChild(content)))
            cleanup <- AscentApp.mount(ui, parent)
            n0      <- inner.observerCount
            _       <- cleanup.cancelAll
            n1      <- inner.observerCount
          yield assertTrue(n0 == 1, n1 == 0)
        }
      },
    ),
    suite("When")(
      test("renders the body when cond is initially true; only an anchor when initially false") {
        withParent { parent =>
          for
            visible <- sq(true)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(UI.When(visible, () => UI.Element("p", Vector.empty, Vector(UI.Text("body"))))),
            )
            _ <- AscentApp.mount(ui, parent)
            divEl          = parent.asInstanceOf[js.Dynamic].firstChild.asInstanceOf[dom.Node]
            initialVisible = visibleChildCount(divEl)
            _ <- visible.set(false)
            afterFalse = visibleChildCount(divEl)
            _ <- visible.set(true)
            afterTrue = visibleChildCount(divEl)
          yield assertTrue(
            initialVisible == 1,
            afterFalse == 0,
            afterTrue == 1,
          )
        }
      },
      test("body thunk is re-evaluated each true-transition (per-render local state stays fresh)") {
        withParent { parent =>
          for
            visible <- sq(false)
            built   <- Ref.make(0)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(
                UI.When(
                  visible,
                  () =>
                    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(built.update(_ + 1)).getOrThrow())
                    UI.Text("body"),
                )
              ),
            )
            _  <- AscentApp.mount(ui, parent)
            n0 <- built.get
            _  <- visible.set(true)
            n1 <- built.get
            _  <- visible.set(false)
            _  <- visible.set(true)
            n2 <- built.get
          yield assertTrue(n0 == 0, n1 == 1, n2 == 2)
        }
      },
    ),
    suite("Nested boundaries")(
      test("a ReactiveChild containing a ReactiveText: cleanup cascades on outer swap") {
        withParent { parent =>
          for
            innerName  <- sq("alice")
            outerInner <- sq[UI[Any]](UI.Element("span", Vector.empty, Vector(UI.ReactiveText(innerName))))
            ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.ReactiveChild(outerInner)))
            _  <- AscentApp.mount(ui, parent)
            n0 <- innerName.observerCount
            _  <- outerInner.set(UI.Text("static"))
            n1 <- innerName.observerCount
          yield assertTrue(n0 == 1, n1 == 0)
        }
      }
    ),
  )
end MountDynamicSpec
