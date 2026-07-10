package ascent.datastar.js

import ascent.ast.UI
import ascent.datastar.Datastar
import ascent.dom
import ascent.js.{Diagnostics, ServerRegionRegistry}
import zio.*
import zio.test.*

import scala.scalajs.js
import ascent.js.AscentApp

/** Applying `patch-elements` to a server region by id: a live region is patched, while a vanished or unknown target is
  * reported as a precise `PatchTargetMissing` diagnostic rather than silently mis-patching.
  */
object ServerRegionPatchSpec extends ZIOSpecDefault:

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

  private def recording[A](body: Ref[Vector[Diagnostics.Violation]] => UIO[A]): UIO[A] =
    for
      ref <- Ref.make(Vector.empty[Diagnostics.Violation])
      prev = Diagnostics.setHandler((v, _) =>
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ref.update(_ :+ v)).getOrThrow())
      )
      a <- body(ref).ensuring(ZIO.succeed(Diagnostics.setHandler(prev)))
    yield a

  private def patchElements(data: String) =
    Datastar.parse(Datastar.PatchElements, data).toOption.get

  def spec = suite("ServerRegion patch")(
    test("patch-elements (inner) fills a live region's interior") {
      withParent { parent =>
        for
          _    <- AscentApp.mount(UI.ServerRegion("cart"), parent)
          _    <- DatastarClient.applyEvent(patchElements("selector #cart\nmode inner\nelements <b>1 item</b>"), null)
          html <- ZIO.succeed(parent.asInstanceOf[js.Dynamic].firstChild.innerHTML.asInstanceOf[String])
        yield assertTrue(html == "<b>1 item</b>")
      }
    },
    test("patching a VANISHED region reports PatchTargetMissing(Vanished), drops the patch") {
      withParent { parent =>
        recording { violations =>
          for
            cleanup <- AscentApp.mount(UI.ServerRegion("gone"), parent)
            _       <- cleanup.cancelAll // region unmounts → status becomes Vanished
            _       <- DatastarClient.applyEvent(patchElements("selector #gone\nmode inner\nelements <b>x</b>"), null)
            vs      <- violations.get
          yield assertTrue(
            vs == Vector(
              Diagnostics.Violation.PatchTargetMissing("gone", ServerRegionRegistry.Status.Vanished)
            )
          )
        }
      }
    },
    test("patching an entirely unknown selector reports PatchTargetMissing(Unknown)") {
      withParent { _ =>
        recording { violations =>
          for
            _  <- DatastarClient.applyEvent(patchElements("selector #never\nmode inner\nelements <b>x</b>"), null)
            vs <- violations.get
          yield assertTrue(
            vs == Vector(
              Diagnostics.Violation.PatchTargetMissing("never", ServerRegionRegistry.Status.Unknown)
            )
          )
        }
      }
    },
    test("a patch with no selector reports PatchMissingSelector") {
      withParent { _ =>
        recording { violations =>
          for
            _  <- DatastarClient.applyEvent(patchElements("mode inner\nelements <b>x</b>"), null)
            vs <- violations.get
          yield assertTrue(vs == Vector(Diagnostics.Violation.PatchMissingSelector))
        }
      }
    },
  ) @@ TestAspect.sequential
end ServerRegionPatchSpec
