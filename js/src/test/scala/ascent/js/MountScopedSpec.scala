package ascent.js

import ascent.ast.UI
import ascent.dom
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** [[UI.Scoped]]: an effectful subtree built at mount time inside a ZIO [[zio.Scope]], so a resource acquired via
  * `ZIO.addFinalizer` is released exactly when the owning boundary (top-level, a When swap, or a ForEach key) tears
  * down.
  */
object MountScopedSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  private def texts(parent: dom.Node): List[String] =
    val cs = parent.asInstanceOf[js.Dynamic].firstChild.childNodes
    (0 until cs.length.asInstanceOf[Int]).flatMap { i =>
      val node = cs.item(i)
      if node.nodeType.asInstanceOf[Int] == 8 then None
      else Some(node.textContent.asInstanceOf[String])
    }.toList

  def spec = suite("Mount (Scoped)")(
    test("runs the builder once at mount and renders its returned subtree") {
      withParent { parent =>
        for
          built <- Ref.make(0)
          ui: UI[Any] = UI.Element(
            "div",
            Vector.empty,
            Vector(
              UI.Scoped(built.update(_ + 1).as(UI.Text("hi")))
            ),
          )
          _ <- AscentApp.mount(ui, parent)
          n <- built.get
        yield assertTrue(n == 1, texts(parent) == List("hi"))
      }
    },
    test("a Scope finalizer runs when the top-level mount cleanup runs") {
      withParent { parent =>
        for
          torn <- Ref.make(0)
          ui: UI[Any] = UI.Element(
            "div",
            Vector.empty,
            Vector(
              UI.Scoped(ZIO.addFinalizer(torn.update(_ + 1)).as(UI.Text("x")))
            ),
          )
          cleanup <- AscentApp.mount(ui, parent)
          before  <- torn.get
          _       <- cleanup.cancelAll
          after   <- torn.get
        yield assertTrue(before == 0, after == 1)
      }
    },
    test("a Scoped under a When tears down when the When goes false") {
      withParent { parent =>
        for
          torn <- Ref.make(0)
          cond <- sq(true)
          scopedBody: UI[Any] = UI.Scoped(ZIO.addFinalizer(torn.update(_ + 1)).as(UI.Text("shown")))
          ui: UI[Any]         = UI.Element("div", Vector.empty, Vector(UI.When(cond, () => scopedBody)))
          _      <- AscentApp.mount(ui, parent)
          shown  <- torn.get
          _      <- cond.set(false)
          hidden <- torn.get
        yield assertTrue(shown == 0, hidden == 1)
      }
    },
    test("per-row Scoped: a ForEach key departing tears down ONLY that row's scope") {
      withParent { parent =>
        for
          tornByKey <- Ref.make(Map.empty[String, Int])
          items     <- sq(Seq("a", "b", "c"))
          row = (k: String) =>
            (UI.Scoped(
              ZIO
                .addFinalizer(tornByKey.update(m => m.updated(k, m.getOrElse(k, 0) + 1)))
                .as(UI.Element("li", Vector.empty, Vector(UI.Text(k))))
            ): UI[Any])
          ui: UI[Any] = UI.Element("ul", Vector.empty, Vector(UI.ForEach(items, identity, row)))
          _       <- AscentApp.mount(ui, parent)
          atStart <- tornByKey.get
          _       <- items.set(Seq("a", "c"))
          afterB  <- tornByKey.get
          _       <- items.set(Seq("a"))
          afterC  <- tornByKey.get
        yield assertTrue(
          atStart.isEmpty,
          afterB == Map("b" -> 1),
          afterC == Map("b" -> 1, "c" -> 1),
          texts(parent) == List("a"),
        )
      }
    },
  )
end MountScopedSpec
