package ascent.js

import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** [[Canvas]] is typed sugar over the canvas facades + lifecycle hooks: `Canvas.element` runs a one-shot setup after
  * mount, `Canvas.animated` runs a per-frame draw loop cancelled on unmount. Both wire HiDPI sizing so callers think in
  * CSS pixels while the backing buffer is `cssSize * devicePixelRatio`.
  */
object CanvasSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Canvas helper")(
    test("Canvas.element produces a UI whose mount-time setup gets the typed canvas + 2D context") {
      withParent { parent =>
        for
          observed <- Ref.make[Option[(Int, Int)]](None)
          ui = Canvas.element(64, 32) { (canvas, _) =>
            observed.set(Some(canvas.width -> canvas.height))
          }
          _   <- AscentApp.mount(ui, parent)
          got <- observed.get
        yield assertTrue(got.isDefined, got.exists(_._1 > 0), got.exists(_._2 > 0))
      }
    },
    test("Canvas.element renders a real <canvas> element with css-pixel inline width/height") {
      withParent { parent =>
        for
          ui <- ZIO.succeed(Canvas.element(80, 40)((_, _) => ZIO.unit))
          _  <- AscentApp.mount(ui, parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
        yield assertTrue(
          el.tagName.asInstanceOf[String].toLowerCase == "canvas",
          el.style.width.asInstanceOf[String] == "80px",
          el.style.height.asInstanceOf[String] == "40px",
        )
      }
    },
    test("Canvas.animated registers a requestAnimationFrame on mount and cancels on unmount") {
      // jsdom's rAF doesn't fire callbacks reliably, so we spy on window's rAF/cancel rather than await a frame.
      withParent { parent =>
        ZIO
          .succeed {
            val win         = dom.window.asInstanceOf[scala.scalajs.js.Dynamic]
            val origRaf     = win.requestAnimationFrame
            val origCancel  = win.cancelAnimationFrame
            val rafCalls    = scala.collection.mutable.ArrayBuffer.empty[Int]
            val cancelCalls = scala.collection.mutable.ArrayBuffer.empty[Int]
            var nextId      = 1
            win.requestAnimationFrame = ((_: scala.scalajs.js.Function1[Double, Unit]) =>
              val id = nextId; nextId += 1
              rafCalls += id
              id
            ): scala.scalajs.js.Function1[scala.scalajs.js.Function1[Double, Unit], Int]
            win.cancelAnimationFrame = ((id: Int) => cancelCalls += id): scala.scalajs.js.Function1[Int, Unit]
            (origRaf, origCancel, rafCalls, cancelCalls)
          }
          .flatMap { case (origRaf, origCancel, rafCalls, cancelCalls) =>
            val win  = dom.window.asInstanceOf[scala.scalajs.js.Dynamic]
            val ui   = Canvas.animated(64, 32)((_, _) => ())
            val test = for
              cleanup <- AscentApp.mount(ui, parent)
              mountedRafCount    = rafCalls.size
              mountedCancelCount = cancelCalls.size
              firstRafId         = rafCalls.headOption
              _ <- cleanup.cancelAll
              unmountedCancelCount = cancelCalls.size
              cancelledIds         = cancelCalls.toList
            yield assertTrue(
              mountedRafCount >= 1,
              firstRafId.isDefined,
              mountedCancelCount == 0,
              unmountedCancelCount == 1,
              cancelledIds == firstRafId.toList,
            )
            // Restore originals so other tests aren't affected.
            test.ensuring(ZIO.succeed {
              win.requestAnimationFrame = origRaf
              win.cancelAnimationFrame = origCancel
            })
          }
      }
    },
  )
end CanvasSpec
