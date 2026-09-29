package ascent.js

import ascent.ast.{Attr, UI}
import ascent.dom
import ascent.domtypes.{Attrs, AttrValue}
import ascent.dsl.*
import zio.*

import scala.scalajs.js

/** Typed `<canvas>` helper: hides the [[Attr.OnMount]] / [[Attr.OnUnmount]] plumbing for canvas drawing and gives the
  * user code typed `dom.HTMLCanvasElement` + `dom.CanvasRenderingContext2D` references — no `js.Dynamic` at the call
  * site.
  *
  * Two factories cover the common shapes:
  *
  *   - [[Canvas.element]] — one-shot. The setup callback fires once at mount with the live canvas + context. Use for
  *     static drawings or for wiring a third-party library that drives its own redraw cycle.
  *   - [[Canvas.animated]] — animation loop. The draw callback fires on every animation frame with `(ctx, tMillis)`;
  *     the loop is cancelled on unmount via `cancelAnimation- Frame`. Use for live visuals — pulsing accents, gauges,
  *     particle systems, etc.
  *
  * Both factories handle HiDPI sizing automatically: the backing pixel buffer is sized at
  * `cssPixels * devicePixelRatio` and the context is pre-`scale`d so callers can think in CSS pixels regardless of
  * display density.
  */
object Canvas:

  /** A canvas with one-shot setup logic: `setup` runs once at mount with the live canvas and its 2D context. Cleanup
    * that must run on unmount (a third-party library's loop) belongs in a [[Lifecycle.onMountScoped]] on the same
    * element.
    */
  def element(
      cssWidth: Int,
      cssHeight: Int,
  )(setup: (dom.HTMLCanvasElement, dom.CanvasRenderingContext2D) => UIO[Unit]): UI[Any] =
    UI.Element(
      "canvas",
      Vector(
        Attrs.width(cssWidth),
        Attrs.height(cssHeight),
        // Inline style so the rendered size (in CSS pixels) matches what the caller asked
        // for — independent of the (possibly DPR-scaled) backing buffer.
        Attr.StaticAttr("style", AttrValue.Str(s"width:${cssWidth}px;height:${cssHeight}px;")),
        Lifecycle.onMount[dom.HTMLCanvasElement] { canvas =>
          withHiDpiContext(canvas, cssWidth, cssHeight)(ctx => setup(canvas, ctx))
        },
      ),
      Vector.empty,
    )

  /** A canvas that runs `draw(ctx, tMillis)` on every animation frame. The draw callback is synchronous (returns
    * `Unit`, not `UIO[Unit]`) — animation loops fire 60+ times a second, and rAF callbacks run on the browser's
    * animation pipeline; threading a ZIO effect through there is overkill and adds latency. If you need to read state,
    * do it via a `Ref.unsafe` inside the closure.
    *
    * The loop is cancelled when the element unmounts (the OnUnmount hook calls `window.cancelAnimationFrame`). No
    * leaks, no zombie loops.
    */
  def animated(
      cssWidth: Int,
      cssHeight: Int,
  )(draw: (dom.CanvasRenderingContext2D, Double) => Unit): UI[Any] =
    // Loop state — set on mount, read on unmount. We carry the rAF id (mutable) and a
    // start-time stamp (read-only after mount) in a small holder.
    final class LoopState:
      var rafId: Int    = 0
      var start: Double = 0.0
    val state = new LoopState

    UI.Element(
      "canvas",
      Vector(
        Attrs.width(cssWidth),
        Attrs.height(cssHeight),
        Attr.StaticAttr("style", AttrValue.Str(s"width:${cssWidth}px;height:${cssHeight}px;")),
        Lifecycle.onMount[dom.HTMLCanvasElement] { canvas =>
          withHiDpiContext(canvas, cssWidth, cssHeight) { ctx =>
            ZIO.succeed {
              state.start = dom.window.performance.now()
              // Recursive scheduler — each frame schedules the next.
              lazy val tick: js.Function1[Double, Unit] = (now: Double) =>
                draw(ctx, now - state.start)
                state.rafId = dom.window.requestAnimationFrame(tick)
              state.rafId = dom.window.requestAnimationFrame(tick)
            }
          }
        },
        Attr.OnUnmount { _ =>
          ZIO.succeed {
            // Idempotent: cancelAnimationFrame on an already-fired or unknown id is a no-op.
            if state.rafId != 0 then dom.window.cancelAnimationFrame(state.rafId)
            state.rafId = 0
          }
        },
      ),
      Vector.empty,
    )
  end animated

  /** Size the backing buffer to `css * devicePixelRatio` and pre-`scale` the 2D context, so `use` draws in CSS pixels
    * and still renders crisply on high-density displays. A canvas the browser gives no 2D context skips `use`, with a
    * warning.
    */
  private def withHiDpiContext(canvas: dom.HTMLCanvasElement, cssWidth: Int, cssHeight: Int)(
      use: dom.CanvasRenderingContext2D => UIO[Unit]
  ): UIO[Unit] =
    ZIO.suspendSucceed {
      val dpr = dom.window.devicePixelRatio
      canvas.width = (cssWidth * dpr).toInt
      canvas.height = (cssHeight * dpr).toInt
      canvas.getContext(dom.CanvasContextId.TwoD) match
        case Some(ctx) =>
          ctx.scale(dpr, dpr)
          use(ctx)
        case None => ZIO.logWarning("this <canvas> has no 2D context, so nothing is drawn on it")
    }
end Canvas
