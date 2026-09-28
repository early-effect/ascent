package ascent.dom

/** An `OffscreenCanvas` context id, typed by the context `getContext` answers for it and the options dictionary it
  * reads (HTML §4.12.5.3). The offscreen 2D context is an `OffscreenCanvasRenderingContext2D`, which is why this is not
  * [[CanvasContextId]]: `offscreen.getContext(OffscreenContextId.TwoD)` is an
  * `Option[OffscreenCanvasRenderingContext2D]`. `None` is the spec's `null`: the canvas already holds a context of
  * another kind, or the browser cannot make this one.
  */
opaque type OffscreenContextId[C, O] = String

object OffscreenContextId:
  val TwoD: OffscreenContextId[OffscreenCanvasRenderingContext2D, CanvasRenderingContext2DSettings] = "2d"

  val BitmapRenderer: OffscreenContextId[ImageBitmapRenderingContext, ImageBitmapRenderingContextSettings] =
    "bitmaprenderer"

  val WebGL: OffscreenContextId[WebGLRenderingContext, WebGLContextAttributes] = "webgl"

  val WebGL2: OffscreenContextId[WebGL2RenderingContext, WebGLContextAttributes] = "webgl2"

  /** WebGPU reads no options at `getContext` (a `GPUCanvasContext` is set up by `configure`), so none can be passed. */
  val WebGPU: OffscreenContextId[GPUCanvasContext, Nothing] = "webgpu"
end OffscreenContextId
