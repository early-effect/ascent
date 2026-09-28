package ascent.dom

import zio.*
import zio.test.*

/** What the DOM facade claims about types that only a real browser can check: jsdom has no `OffscreenCanvas` and no
  * `CanvasRenderingContext2D` global to test a context against. Each typed id's context is the interface it names.
  */
object BrowserFacadeSpec extends ZIOSpecDefault:

  def spec = suite("DOM facade in a real browser")(
    test("CanvasContextId.TwoD answers a real CanvasRenderingContext2D for its canvas") {
      ZIO.succeed {
        val canvas = document.createElement(HtmlTag.canvas)
        val ctx    = canvas.getContext(CanvasContextId.TwoD)
        assertTrue(
          ctx.exists(_.isInstanceOf[CanvasRenderingContext2D]),
          ctx.exists(c => scala.scalajs.js.special.strictEquals(c.canvas, canvas)),
        )
      }
    },
    test("OffscreenContextId.TwoD answers a real OffscreenCanvasRenderingContext2D, not the element's 2D context") {
      ZIO.succeed {
        val ctx = new OffscreenCanvas(8, 8).getContext(OffscreenContextId.TwoD)
        assertTrue(
          ctx.exists(_.isInstanceOf[OffscreenCanvasRenderingContext2D]),
          !ctx.exists(_.isInstanceOf[CanvasRenderingContext2D]),
        )
      }
    },
    test("a canvas that already holds a 2D context answers None for another kind") {
      ZIO.succeed {
        val offscreen = new OffscreenCanvas(8, 8)
        val first     = offscreen.getContext(OffscreenContextId.TwoD)
        assertTrue(first.isDefined, offscreen.getContext(OffscreenContextId.BitmapRenderer).isEmpty)
      }
    },
  ) @@ TestAspect.timeout(60.seconds)
end BrowserFacadeSpec
