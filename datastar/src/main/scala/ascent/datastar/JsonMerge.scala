package ascent.datastar

import zio.Chunk
import zio.json.ast.Json

/** RFC 7386 JSON Merge Patch over zio-json's `Json` AST.
  *
  * Datastar's `patch-signals` carries a merge patch: an object recursively merges into the target, a member whose value
  * is `null` deletes that member, and any non-object patch replaces the target wholesale. This is the value-level merge
  * the [[SignalStore]] applies before decoding to a typed signal. (Per-element array patching is out of scope — RFC
  * 7386 has none, and datastar models lists as whole values.)
  */
object JsonMerge:

  /** Apply `patch` to `target` per RFC 7386, returning the merged JSON. */
  def merge(target: Json, patch: Json): Json =
    patch match
      case patchObj: Json.Obj =>
        val base = target match
          case o: Json.Obj => o.fields
          case _           => Chunk.empty[(String, Json)] // non-object target: start fresh
        val acc = scala.collection.mutable.LinkedHashMap.from(base)
        patchObj.fields.foreach { (k, v) =>
          v match
            case Json.Null => acc.remove(k)
            case _         => acc.update(k, merge(acc.getOrElse(k, Json.Obj()), v))
        }
        Json.Obj(Chunk.fromIterable(acc))
      case _ =>
        // Non-object patch replaces the target entirely.
        patch
end JsonMerge
