package ascent.js

import ascent.ast.UI
import ascent.dom
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** ForEach keyed reconciliation: unchanged keys reuse their existing DOM nodes (preserving focus/caret on edited rows);
  * only new keys build fresh subtrees.
  */
object MountForEachSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  /** The non-comment children of `parent`'s firstChild as a list of textContent strings. */
  private def visibleTexts(parent: dom.Node): List[String] =
    val container = parent.asInstanceOf[js.Dynamic].firstChild
    val cs        = container.childNodes
    val n         = cs.length.asInstanceOf[Int]
    (0 until n).flatMap { i =>
      val node = cs.item(i)
      if node.nodeType.asInstanceOf[Int] == 8 then None
      else Some(node.textContent.asInstanceOf[String])
    }.toList

  /** Same but as identity references (use for proving node reuse). */
  private def visibleNodes(parent: dom.Node): Vector[js.Any] =
    val container = parent.asInstanceOf[js.Dynamic].firstChild
    val cs        = container.childNodes
    val n         = cs.length.asInstanceOf[Int]
    (0 until n).flatMap { i =>
      val node = cs.item(i)
      if node.nodeType.asInstanceOf[Int] == 8 then None
      else Some(node.asInstanceOf[js.Any])
    }.toVector

  private def li(text: String): UI[Any] =
    UI.Element("li", Vector.empty, Vector(UI.Text(text)))

  private def feUi(items: ascent.squawk.Squawk[Seq[String]]): UI[Any] =
    UI.Element("ul", Vector.empty, Vector(UI.ForEach(items, identity, li)))

  def spec = suite("Mount (ForEach)")(
    test("initial render builds one subtree per item between the anchors") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b", "c"))
          _     <- AscentApp.mount(feUi(items), parent)
        yield assertTrue(visibleTexts(parent) == List("a", "b", "c"))
      }
    },
    test("appending items inserts new nodes between the existing ones and the end anchor") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b"))
          _     <- AscentApp.mount(feUi(items), parent)
          _     <- items.set(Seq("a", "b", "c"))
        yield assertTrue(visibleTexts(parent) == List("a", "b", "c"))
      }
    },
    test("removing items removes their nodes and cancels their per-item cleanup (leak check)") {
      // Each row subscribes to a shared inner Squawk; its observerCount tells us the removed row's cleanup ran.
      withParent { parent =>
        for
          inner <- sq("hi")
          items <- sq(Seq("a", "b", "c"))
          ui: UI[Any] = UI.Element(
            "ul",
            Vector.empty,
            Vector(
              UI.ForEach(
                items,
                identity,
                _ => UI.Element("li", Vector.empty, Vector(UI.ReactiveText(inner))),
              )
            ),
          )
          _  <- AscentApp.mount(ui, parent)
          n0 <- inner.observerCount
          _  <- items.set(Seq("a"))
          n1 <- inner.observerCount
        yield assertTrue(n0 == 3, n1 == 1)
      }
    },
    test("reordering keeps the SAME node per key so a focused descendant stays attached") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b", "c"))
          _     <- AscentApp.mount(feUi(items), parent)
          before = visibleNodes(parent)
          _ <- items.set(Seq("c", "b", "a"))
          after = visibleNodes(parent)
        yield assertTrue(
          before.size == 3,
          after.size == 3,
          (after(0) eq before(2)),
          (after(1) eq before(1)),
          (after(2) eq before(0)),
          visibleTexts(parent) == List("c", "b", "a"),
        )
      }
    },
    test("inserting at the head shifts existing nodes without rebuilding them") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b"))
          _     <- AscentApp.mount(feUi(items), parent)
          before = visibleNodes(parent)
          _ <- items.set(Seq("z", "a", "b"))
          after = visibleNodes(parent)
        yield assertTrue(
          after.size == 3,
          (after(1) eq before(0)),
          (after(2) eq before(1)),
          visibleTexts(parent) == List("z", "a", "b"),
        )
      }
    },
    test("clearing the list empties the parent entirely (no orphaned content, no anchors)") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b", "c"))
          _     <- AscentApp.mount(feUi(items), parent)
          _     <- items.set(Seq.empty)
        yield
          val ul = parent.asInstanceOf[js.Dynamic].firstChild
          val cs = ul.childNodes
          assertTrue(cs.length.asInstanceOf[Int] == 0)
      }
    },
    test("duplicate keys do not crash; the first occurrence wins (last is dropped)") {
      withParent { parent =>
        for
          items <- sq(Seq("a", "b", "a"))
          _     <- AscentApp.mount(feUi(items), parent)
        yield assertTrue(visibleTexts(parent) == List("a", "b"))
      }
    },
    test("Mount cleanup tears down every per-item cleanup, leaving zero observers") {
      withParent { parent =>
        for
          inner <- sq("hi")
          items <- sq(Seq("a", "b", "c"))
          ui: UI[Any] = UI.Element(
            "ul",
            Vector.empty,
            Vector(
              UI.ForEach(
                items,
                identity,
                _ => UI.Element("li", Vector.empty, Vector(UI.ReactiveText(inner))),
              )
            ),
          )
          cleanup <- AscentApp.mount(ui, parent)
          n0      <- inner.observerCount
          _       <- cleanup.cancelAll
          n1      <- inner.observerCount
        yield assertTrue(n0 == 3, n1 == 0)
      }
    },
  )
end MountForEachSpec
