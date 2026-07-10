package ascent.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

/** Hand-written global re-exports. Most of the DOM facade is generated from W3C IDL (see [[generated.Interfaces]]);
  * only `globalThis` access lives here, since `@JSGlobalScope` can't be expressed as a generated `@JSGlobal` binding.
  */

@js.native
@JSGlobalScope
object Globals extends js.Object:
  val document: Document = js.native
  val window: Window     = js.native

/** So callers write `dom.document` rather than `Globals.document`. */
inline def document: Document = Globals.document

inline def window: Window = Globals.window
