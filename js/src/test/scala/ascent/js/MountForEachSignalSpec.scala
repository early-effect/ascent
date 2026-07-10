package ascent.js

import ascent.ast.UI
import ascent.dom
import ascent.squawk.{Eq, sq}
import zio.*
import zio.test.*

import scala.scalajs.js

/** `forEachSignal` is the fine-grained list: each surviving key keeps a stable, memoized `Squawk[A]` the engine feeds
  * on every parent emit, so a row's `render` runs once per key and only its bound boundaries repaint.
  */
object MountForEachSignalSpec extends ZIOSpecDefault:

  final case class Row(id: String, label: String) derives Eq

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  private def container(parent: dom.Node): js.Dynamic =
    parent.asInstanceOf[js.Dynamic].firstChild

  private def visibleTexts(parent: dom.Node): List[String] =
    val cs = container(parent).childNodes
    val n  = cs.length.asInstanceOf[Int]
    (0 until n).flatMap { i =>
      val node = cs.item(i)
      if node.nodeType.asInstanceOf[Int] == 8 then None
      else Some(node.textContent.asInstanceOf[String])
    }.toList

  private def liNodes(parent: dom.Node): Vector[js.Any] =
    val cs = container(parent).childNodes
    val n  = cs.length.asInstanceOf[Int]
    (0 until n).flatMap { i =>
      val node = cs.item(i)
      if node.nodeType.asInstanceOf[Int] == 8 then None else Some(node.asInstanceOf[js.Any])
    }.toVector

  /** A `<ul>` whose rows bind their label to the per-item signal via ReactiveText. */
  private def listUi(items: ascent.squawk.Squawk[Seq[Row]]): UI[Any] =
    UI.Element(
      "ul",
      Vector.empty,
      Vector(
        UI.ForEachSignal[Row, Any](
          items,
          _.id,
          (_, _, sig) => UI.Element("li", Vector.empty, Vector(UI.ReactiveText(sig.map(_.label)))),
        )
      ),
    )

  def spec = suite("Mount (forEachSignal — per-item signal)")(
    test("initial render shows each row's label") {
      withParent { parent =>
        for
          items <- sq(Seq(Row("a", "Apple"), Row("b", "Banana")))
          _     <- AscentApp.mount(listUi(items), parent)
        yield assertTrue(visibleTexts(parent) == List("Apple", "Banana"))
      }
    },
    test("mutating ONE row's field repaints only that row's text IN PLACE — no DOM node recreated") {
      withParent { parent =>
        for
          items <- sq(Seq(Row("a", "Apple"), Row("b", "Banana"), Row("c", "Cherry")))
          _     <- AscentApp.mount(listUi(items), parent)
          before = liNodes(parent)
          _ <- items.set(Seq(Row("a", "Apple"), Row("b", "Blueberry"), Row("c", "Cherry")))
          after = liNodes(parent)
        yield assertTrue(
          visibleTexts(parent) == List("Apple", "Blueberry", "Cherry"),
          before.length == 3,
          after.length == 3,
          before(0) eq after(0),
          before(1) eq after(1),
          before(2) eq after(2),
        )
      }
    },
    test("an unchanged item across a parent emit does NOT recreate or repaint its row") {
      withParent { parent =>
        for
          items <- sq(Seq(Row("a", "Apple"), Row("b", "Banana")))
          _     <- AscentApp.mount(listUi(items), parent)
          nodes0 = liNodes(parent)
          // Stamp row a's <li>; a needless rebuild/repaint would drop this marker.
          _ <- ZIO.succeed(nodes0(0).asInstanceOf[js.Dynamic].setAttribute("data-marker", "x"))
          _ <- items.set(Seq(Row("a", "Apple"), Row("b", "Berry")))
          nodes1 = liNodes(parent)
          marker = nodes1(0).asInstanceOf[js.Dynamic].getAttribute("data-marker").asInstanceOf[String]
        yield assertTrue(
          nodes0(0) eq nodes1(0),
          marker == "x",
          visibleTexts(parent) == List("Apple", "Berry"),
        )
      }
    },
    test("render runs exactly once per key, even as the row's value changes") {
      withParent { parent =>
        for
          built <- Ref.make(0)
          items <- sq(Seq(Row("a", "Apple")))
          ui: UI[Any] = UI.Element(
            "ul",
            Vector.empty,
            Vector(
              UI.ForEachSignal[Row, Any](
                items,
                _.id,
                (_, _, sig) =>
                  // render is pure, so build count is tracked via a mount-time hook, not in render.
                  UI.Element(
                    "li",
                    Vector(ascent.ast.Attr.OnMount(_ => built.update(_ + 1))),
                    Vector(UI.ReactiveText(sig.map(_.label))),
                  ),
              )
            ),
          )
          _  <- AscentApp.mount(ui, parent)
          b0 <- built.get
          _  <- items.set(Seq(Row("a", "Apricot")))
          _  <- items.set(Seq(Row("a", "Avocado")))
          b1 <- built.get
        yield assertTrue(b0 == 1, b1 == 1, visibleTexts(parent) == List("Avocado"))
      }
    },
    test("a departing key tears down its row (DOM removed) and its per-item cleanup runs") {
      withParent { parent =>
        for
          items <- sq(Seq(Row("a", "Apple"), Row("b", "Banana"), Row("c", "Cherry")))
          _     <- AscentApp.mount(listUi(items), parent)
          _     <- items.set(Seq(Row("a", "Apple"), Row("c", "Cherry")))
        yield assertTrue(visibleTexts(parent) == List("Apple", "Cherry"))
      }
    },
    test("reordering keys reuses the existing row nodes (move, not rebuild)") {
      withParent { parent =>
        for
          items <- sq(Seq(Row("a", "Apple"), Row("b", "Banana"), Row("c", "Cherry")))
          _     <- AscentApp.mount(listUi(items), parent)
          before = liNodes(parent)
          _ <- items.set(Seq(Row("c", "Cherry"), Row("a", "Apple"), Row("b", "Banana")))
          after = liNodes(parent)
        yield assertTrue(
          visibleTexts(parent) == List("Cherry", "Apple", "Banana"),
          before(0) eq after(1),
          before(2) eq after(0),
        )
      }
    },
  )
end MountForEachSignalSpec
