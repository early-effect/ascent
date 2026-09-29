package ascent.dom

/** A `<canvas>` context id, typed by the context `getContext` answers for it and the options dictionary it reads (HTML
  * §4.12.5). WebIDL types the result as the whole `RenderingContext?` union; the id carries what the spec's prose says,
  * so `canvas.getContext(CanvasContextId.TwoD)` is an `Option[CanvasRenderingContext2D]`. `None` is the spec's `null`:
  * the canvas already holds a context of another kind, or the browser cannot make this one.
  */
opaque type CanvasContextId[C, O] = String

object CanvasContextId:
  val TwoD: CanvasContextId[CanvasRenderingContext2D, CanvasRenderingContext2DSettings] = "2d"

  val BitmapRenderer: CanvasContextId[ImageBitmapRenderingContext, ImageBitmapRenderingContextSettings] =
    "bitmaprenderer"

  val WebGL: CanvasContextId[WebGLRenderingContext, WebGLContextAttributes] = "webgl"

  val WebGL2: CanvasContextId[WebGL2RenderingContext, WebGLContextAttributes] = "webgl2"

  /** WebGPU reads no options at `getContext` (a `GPUCanvasContext` is set up by `configure`), so none can be passed. */
  val WebGPU: CanvasContextId[GPUCanvasContext, Nothing] = "webgpu"
end CanvasContextId
