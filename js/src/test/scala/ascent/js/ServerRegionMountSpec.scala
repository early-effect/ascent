package ascent.js

import ascent.ast.UI
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Mounting a [[UI.ServerRegion]]: emits an empty id-stamped container, registers it in the [[ServerRegionRegistry]],
  * never reconciles inside it, deregisters on unmount, and fires a [[Diagnostics]] violation on client DOM mutation.
  */
object ServerRegionMountSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed {
        ServerRegionRegistry.clearForTest()
        Diagnostics.resetHandler()
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(p =>
      ZIO.succeed {
        p.parentNode.removeChild(p)
        ServerRegionRegistry.clearForTest()
        Diagnostics.resetHandler()
      }.unit
    )(use)

  /** Install a recording diagnostics handler for the duration of `body`. */
  private def recording[A](body: Ref[Vector[Diagnostics.Violation]] => UIO[A]): UIO[A] =
    for
      ref <- Ref.make(Vector.empty[Diagnostics.Violation])
      prev = Diagnostics.setHandler((v, _) =>
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ref.update(_ :+ v)).getOrThrow())
      )
      a <- body(ref).ensuring(ZIO.succeed(Diagnostics.setHandler(prev)))
    yield a

  def spec = suite("Mount (ServerRegion)")(
    test("mounts an empty container with the region id and registers it Live") {
      withParent { parent =>
        for
          _      <- AscentApp.mount(UI.ServerRegion("cart"), parent)
          status <- ZIO.succeed(ServerRegionRegistry.status("cart"))
          look   <- ZIO.succeed(ServerRegionRegistry.lookup("cart"))
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(
            el.getAttribute("id").asInstanceOf[String] == "cart",
            el.childNodes.length.asInstanceOf[Int] == 0,
            status == ServerRegionRegistry.Status.Live,
            look.isDefined,
          )
      }
    },
    test("unmount (cleanup) deregisters the region as Vanished") {
      withParent { parent =>
        for
          cleanup <- AscentApp.mount(UI.ServerRegion("panel"), parent)
          liveBefore = ServerRegionRegistry.status("panel")
          _ <- cleanup.cancelAll
          statusAfter = ServerRegionRegistry.status("panel")
        yield assertTrue(
          liveBefore == ServerRegionRegistry.Status.Live,
          statusAfter == ServerRegionRegistry.Status.Vanished,
        )
      }
    },
    test("a When that drops the region marks it Vanished, not Unknown") {
      withParent { parent =>
        for
          show <- ascent.squawk.sq(true)
          ui: UI[Any] = UI.When(show, () => UI.ServerRegion("toggle"))
          _ <- AscentApp.mount(ui, parent)
          live = ServerRegionRegistry.status("toggle")
          _ <- show.set(false)
          gone = ServerRegionRegistry.status("toggle")
        yield assertTrue(live == ServerRegionRegistry.Status.Live, gone == ServerRegionRegistry.Status.Vanished)
      }
    },
    test("regionContaining finds the region for a node inside it") {
      withParent { parent =>
        for
          _     <- AscentApp.mount(UI.ServerRegion("host"), parent)
          inOut <- ZIO.succeed {
            import JsDomOps.given // DomOps[dom.Node] for the generic ancestor-walk
            val region = parent.asInstanceOf[js.Dynamic].firstChild.asInstanceOf[dom.Element]
            val inner  = dom.document.createElement("span")
            region.appendChild(inner)
            // Ascribe to dom.Node so regionContaining infers N = Node (matching the given), not the narrower Element.
            (
              ServerRegionRegistry.regionContaining(inner: dom.Node),
              ServerRegionRegistry.regionContaining(parent: dom.Node),
            )
          }
        yield assertTrue(inOut._1 == Some("host"), inOut._2 == None)
      }
    },
    test("Dom.addClass inside a server region fires a ClientMutatedServerRegion violation") {
      withParent { parent =>
        recording { violations =>
          for
            _ <- AscentApp.mount(UI.ServerRegion("zone"), parent)
            region = parent.asInstanceOf[js.Dynamic].firstChild.asInstanceOf[dom.Element]
            inner  = dom.document.createElement("span")
            _  <- ZIO.succeed(region.appendChild(inner))
            _  <- ZIO.succeed(Dom.addClass(inner, "highlight"))
            vs <- violations.get
          yield assertTrue(
            vs == Vector(Diagnostics.Violation.ClientMutatedServerRegion("zone"))
          )
        }
      }
    },
    test("Dom.addClass OUTSIDE any region fires no violation") {
      withParent { parent =>
        recording { violations =>
          for
            _ <- AscentApp.mount(UI.ServerRegion("zone"), parent)
            outside = dom.document.createElement("span")
            _  <- ZIO.succeed(parent.appendChild(outside))
            _  <- ZIO.succeed(Dom.addClass(outside, "ok"))
            vs <- violations.get
          yield assertTrue(vs.isEmpty)
        }
      }
    },
    test("duplicate region id is reported") {
      withParent { parent =>
        recording { violations =>
          for
            _  <- AscentApp.mount(UI.Fragment[Any](Vector(UI.ServerRegion("dup"), UI.ServerRegion("dup"))), parent)
            vs <- violations.get
          yield assertTrue(vs.contains(Diagnostics.Violation.DuplicateRegion("dup")))
        }
      }
    },
  ) @@ TestAspect.sequential
end ServerRegionMountSpec
