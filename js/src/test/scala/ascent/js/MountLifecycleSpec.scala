package ascent.js

import ascent.ast.{Attr, UI}
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Element lifecycle hooks: [[Attr.OnMount]] fires once after insertion (layout/sizes readable), [[Attr.OnUnmount]]
  * once just before removal; both receive the real element, and a failing hook doesn't derail its siblings.
  */
object MountLifecycleSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount lifecycle (OnMount)")(
    test("fires exactly once per mount, receiving the real element") {
      withParent { parent =>
        for
          calls <- Ref.make(Vector.empty[String])
          ui: UI[Any] = UI.Element(
            "div",
            Vector(
              Attr.StaticAttr("id", ascent.domtypes.AttrValue.Str("target")),
              Attr.OnMount(el => calls.update(_ :+ el.asInstanceOf[js.Dynamic].id.asInstanceOf[String])),
            ),
            Vector.empty,
          )
          _    <- AscentApp.mount(ui, parent)
          seen <- calls.get
        yield assertTrue(seen == Vector("target"))
      }
    },
    test("fires AFTER the element is inserted into the parent (so size + layout are readable)") {
      withParent { parent =>
        for
          inDocument <- Ref.make(false)
          ui: UI[Any] = UI.Element(
            "div",
            Vector(Attr.OnMount(el => inDocument.set(el.asInstanceOf[js.Dynamic].isConnected.asInstanceOf[Boolean]))),
            Vector.empty,
          )
          _        <- AscentApp.mount(ui, parent)
          inserted <- inDocument.get
        yield assertTrue(inserted == true)
      }
    },
    test("two OnMount handlers on the same element both fire, in source order") {
      withParent { parent =>
        for
          order <- Ref.make(Vector.empty[Int])
          ui: UI[Any] = UI.Element(
            "span",
            Vector(
              Attr.OnMount(_ => order.update(_ :+ 1)),
              Attr.OnMount(_ => order.update(_ :+ 2)),
            ),
            Vector.empty,
          )
          _   <- AscentApp.mount(ui, parent)
          got <- order.get
        yield assertTrue(got == Vector(1, 2))
      }
    },
    test("a failing OnMount handler does not block subsequent siblings from mounting") {
      withParent { parent =>
        for
          siblingFired <- Ref.make(false)
          ui: UI[Any] = UI.Element(
            "div",
            Vector.empty,
            Vector(
              UI.Element("p", Vector(Attr.OnMount(_ => ZIO.die(new RuntimeException("kaboom")))), Vector.empty),
              UI.Element("p", Vector(Attr.OnMount(_ => siblingFired.set(true))), Vector.empty),
            ),
          )
          _   <- AscentApp.mount(ui, parent)
          got <- siblingFired.get
        yield assertTrue(got == true)
      }
    },
    test("teardown does NOT fire the OnMount hook a second time") {
      withParent { parent =>
        for
          calls <- Ref.make(0)
          ui: UI[Any] = UI.Element("div", Vector(Attr.OnMount(_ => calls.update(_ + 1))), Vector.empty)
          cleanup <- AscentApp.mount(ui, parent)
          n0      <- calls.get
          _       <- cleanup.cancelAll
          n1      <- calls.get
        yield assertTrue(n0 == 1, n1 == 1)
      }
    },
    suite("Attr.OnUnmount")(
      test("fires exactly once on cleanup teardown, receiving the real element") {
        withParent { parent =>
          for
            seenIds <- Ref.make(Vector.empty[String])
            ui: UI[Any] = UI.Element(
              "div",
              Vector(
                Attr.StaticAttr("id", ascent.domtypes.AttrValue.Str("bye")),
                Attr.OnUnmount(el => seenIds.update(_ :+ el.asInstanceOf[js.Dynamic].id.asInstanceOf[String])),
              ),
              Vector.empty,
            )
            cleanup      <- AscentApp.mount(ui, parent)
            duringMount  <- seenIds.get
            _            <- cleanup.cancelAll
            afterCleanup <- seenIds.get
          yield assertTrue(
            duringMount == Vector.empty,
            afterCleanup == Vector("bye"),
          )
        }
      },
      test("two OnUnmount handlers on the same element both fire, in source order") {
        withParent { parent =>
          for
            order <- Ref.make(Vector.empty[Int])
            ui: UI[Any] = UI.Element(
              "span",
              Vector(
                Attr.OnUnmount(_ => order.update(_ :+ 1)),
                Attr.OnUnmount(_ => order.update(_ :+ 2)),
              ),
              Vector.empty,
            )
            cleanup <- AscentApp.mount(ui, parent)
            _       <- cleanup.cancelAll
            got     <- order.get
          yield assertTrue(got == Vector(1, 2))
        }
      },
      test("fires for elements removed mid-life via a dynamic boundary swap (ReactiveChild)") {
        withParent { parent =>
          for
            unmountCount <- Ref.make(0)
            inner: UI[Any] = UI.Element(
              "canvas",
              Vector(Attr.OnUnmount(_ => unmountCount.update(_ + 1))),
              Vector.empty,
            )
            content <- ascent.squawk.sq[ascent.ast.UI[Any]](inner)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(ascent.ast.UI.ReactiveChild(content)),
            )
            _  <- AscentApp.mount(ui, parent)
            n0 <- unmountCount.get
            _  <- content.set(ascent.ast.UI.Text("nothing"))
            n1 <- unmountCount.get
          yield assertTrue(n0 == 0, n1 == 1)
        }
      },
      test("OnUnmount fires for elements removed by a ForEach key drop") {
        import ascent.ast.UI
        import ascent.squawk.sq
        withParent { parent =>
          for
            unmountedKeys <- Ref.make(Vector.empty[String])
            items         <- sq(Seq("a", "b", "c"))
            ui: UI[Any] = UI.Element(
              "ul",
              Vector.empty,
              Vector(
                UI.ForEach(
                  items,
                  identity,
                  k =>
                    UI.Element(
                      "li",
                      Vector(Attr.OnUnmount(_ => unmountedKeys.update(_ :+ k))),
                      Vector.empty,
                    ),
                )
              ),
            )
            _          <- AscentApp.mount(ui, parent)
            _          <- items.set(Seq("a", "c"))
            droppedOne <- unmountedKeys.get
            _          <- items.set(Seq.empty)
            droppedAll <- unmountedKeys.get
          yield assertTrue(
            droppedOne == Vector("b"),
            droppedAll.toSet == Set("a", "b", "c"),
          )
        }
      },
      test("a failing OnUnmount handler does not block sibling unmounts") {
        withParent { parent =>
          for
            siblingFired <- Ref.make(false)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(
                UI.Element("p", Vector(Attr.OnUnmount(_ => ZIO.die(new RuntimeException("kaboom")))), Vector.empty),
                UI.Element("p", Vector(Attr.OnUnmount(_ => siblingFired.set(true))), Vector.empty),
              ),
            )
            cleanup <- AscentApp.mount(ui, parent)
            _       <- cleanup.cancelAll
            got     <- siblingFired.get
          yield assertTrue(got == true)
        }
      },
      test("OnMount + OnUnmount fire in mount-then-unmount order on the same element") {
        withParent { parent =>
          for
            log <- Ref.make(Vector.empty[String])
            ui: UI[Any] = UI.Element(
              "div",
              Vector(
                Attr.OnMount(_ => log.update(_ :+ "mount")),
                Attr.OnUnmount(_ => log.update(_ :+ "unmount")),
              ),
              Vector.empty,
            )
            cleanup      <- AscentApp.mount(ui, parent)
            afterMount   <- log.get
            _            <- cleanup.cancelAll
            afterUnmount <- log.get
          yield assertTrue(
            afterMount == Vector("mount"),
            afterUnmount == Vector("mount", "unmount"),
          )
        }
      },
      test("nested OnUnmount hooks tear down LIFO: the child (registered later) fires before its parent") {
        withParent { parent =>
          for
            log <- Ref.make(Vector.empty[String])
            ui: UI[Any] = UI.Element(
              "div",
              Vector(Attr.OnUnmount(_ => log.update(_ :+ "parent"))),
              Vector(
                UI.Element("span", Vector(Attr.OnUnmount(_ => log.update(_ :+ "child"))), Vector.empty)
              ),
            )
            cleanup <- AscentApp.mount(ui, parent)
            _       <- cleanup.cancelAll
            order   <- log.get
          yield assertTrue(order == Vector("child", "parent"))
        }
      },
    ),
  )
end MountLifecycleSpec
