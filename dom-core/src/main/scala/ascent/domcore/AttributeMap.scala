package ascent.domcore

import scala.collection.mutable

/** An element's live attribute store — one per [[NodeMemoryBase]]-derived instance (concrete elements only; `Node`
  * itself doesn't carry one). Backs BOTH:
  *   - the reflected-attribute accessors the generator auto-implements (`attributes.getOrElse("href", "")` /
  *     `attributes.set("href", value)`, via [[AttrCodec]]) — [[Renderer.memoryImpls]]'s policy;
  *   - `Element`'s own raw-string attribute surface (`getAttribute`/`setAttribute`/`removeAttribute`/`hasAttribute`),
  *     hand-implemented in `ElementOverrides` against the SAME underlying map, so a reflected property and its
  *     equivalent `setAttribute` call observe each other's writes (`el.href = "x"` and `el.setAttribute("href", "x")`
  *     are the same operation on the same element, per spec).
  *
  * Preserves insertion order (a `LinkedHashMap`) so [[names]] / a future `getAttributeNames` matches the real DOM's
  * documented iteration order (declaration order, not alphabetical).
  */
final class AttributeMap:
  private val raw = mutable.LinkedHashMap.empty[String, String]

  /** The attribute's typed value via `codec`, or `default` if absent. Used by generated reflected-attribute getters. */
  def getOrElse[V](name: String, default: V)(using codec: AttrCodec[V]): V =
    raw.get(name).map(codec.decode).getOrElse(default)

  /** Sets the attribute's typed value via `codec`. A `false` [[Boolean]] REMOVES the attribute (presence-coded HTML
    * boolean semantics — `disabled = false` means "not present", not "present with an empty/false string"). Used by
    * generated reflected-attribute setters.
    */
  def set[V](name: String, value: V)(using codec: AttrCodec[V]): Unit =
    value match
      case false => raw.remove(name)
      case _     => raw(name) = codec.encode(value)

  /** The raw stored string, or `None` if absent — backs `Element.getAttribute`. */
  def get(name: String): Option[String] = raw.get(name)

  /** Sets the raw stored string directly — backs `Element.setAttribute`. Unlike the typed [[set]] overload, this NEVER
    * removes on any particular value (an empty string is still a present attribute, matching
    * `ascent.domtypes.Codec.StringAsIs`'s policy) — only [[remove]] / a `Boolean` `false` via the typed path removes.
    */
  def setRaw(name: String, value: String): Unit = raw(name) = value

  /** Removes the attribute entirely — backs `Element.removeAttribute`. A no-op if already absent. */
  def remove(name: String): Unit =
    raw.remove(name); ()

  def contains(name: String): Boolean = raw.contains(name)

  /** Attribute names in declaration order — matches the real DOM's documented iteration order. */
  def names: Seq[String] = raw.keys.toSeq

  /** `(name, value)` pairs in declaration order. */
  def entries: Seq[(String, String)] = raw.toSeq

  def isEmpty: Boolean = raw.isEmpty
end AttributeMap
