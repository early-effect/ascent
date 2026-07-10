package ascent.domtypes

/** A platform-neutral, serialized attribute value.
  *
  * This is what a [[Codec]] produces from a typed Scala value and what the DOM backend consumes when patching a node.
  * It is deliberately tiny and dom-free so it can live in the zero-dependency `dom-types` module and flow into the core
  * AST.
  */
enum AttrValue:
  /** A present attribute/property whose serialized form is `value`. */
  case Str(value: String)

  /** A present boolean-valued *property* (e.g. `checked`), set directly on the DOM node rather than via `setAttribute`.
    * Attribute-style booleans serialize through [[Str]]/[[Absent]].
    */
  case Bool(value: Boolean)

  /** The attribute is absent — the backend should remove it (or leave it unset). */
  case Absent
end AttrValue
