package ascent.datastar

import zio.json.ast.Json

/** The neutral, decoded form of an incoming datastar SSE event — what a [[RemoteDialect]] produces and the client
  * runtime consumes. Kept dialect-agnostic so a non-datastar backend could emit the same shapes.
  */
enum RemoteEvent:
  /** A `patch-signals` event: merge `signals` (an RFC-7386 merge patch) into the client signal store. `onlyIfMissing`
    * sets only signals that don't already exist.
    */
  case PatchSignals(signals: Json.Obj, onlyIfMissing: Boolean)

  /** A `patch-elements` event: apply `html` into the target `selector` (or the element's own id when `None`, per the
    * protocol) using `mode`. `html` is empty for a `Remove`.
    */
  case PatchElements(
      html: String,
      selector: Option[String],
      mode: ElementPatchMode,
      useViewTransition: Boolean,
  )
end RemoteEvent

/** One per-signal update, flattened from a [[RemoteEvent.PatchSignals]] object so the store can route by name. A
  * top-level `null` value is a delete (RFC-7386).
  */
enum SignalPatch:
  case Put(name: String, value: Json, onlyIfMissing: Boolean)
  case Delete(name: String)

object SignalPatch:
  /** Flatten one `patch-signals` object into per-name patches. Top-level keys are signal names; a `Json.Null` value is
    * a delete. Nested objects/values are kept verbatim for the signal's own decoder to interpret (and for value-level
    * merge).
    */
  def fromSignals(obj: Json.Obj, onlyIfMissing: Boolean): Vector[SignalPatch] =
    obj.fields.iterator.map {
      case (name, Json.Null) => SignalPatch.Delete(name)
      case (name, value)     => SignalPatch.Put(name, value, onlyIfMissing)
    }.toVector
end SignalPatch
