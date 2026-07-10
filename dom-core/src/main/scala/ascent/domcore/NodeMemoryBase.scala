package ascent.domcore

import ascent.domcore.generated.{Document, Event, Node}

import scala.collection.mutable

/** The shared kernel every generated `*Memory` class ultimately extends (`EventTargetMemory extends NodeMemoryBase`,
  * and everything else chains up through `NodeMemory`/`EventTargetMemory`). Owns the raw mutable state
  * [[EventTargetOverrides]] (listener storage/dispatch) and [[NodeOverrides]] (tree relationships) both need:
  *
  *   - the listener registry, keyed by event type — `Event => Unit`, the SAME plain-Scala-function shape
  *     `EventTarget.addEventListener`'s structural type resolves to (see `ascent.domgen.DefBuilder.structuralType`'s
  *     callback-resolution branch); any ergonomic wrapping to ascent's own `AscentEvent` is a concern for the layer
  *     above dom-core, not this kernel — dom-core implements the WebIDL contract faithfully;
  *   - the parent pointer and the ordered child list.
  *
  * Both override traits are `self: NodeMemoryBase & Node =>` — they read/mutate this class's fields directly rather
  * than going through `Node`'s own getters, since the getters themselves ARE what `NodeOverrides` implements (a getter
  * can't be its own implementation's data source).
  */
class NodeMemoryBase:
  private[domcore] val listeners: mutable.LinkedHashMap[String, mutable.ArrayBuffer[Event => Unit]] =
    mutable.LinkedHashMap.empty

  private[domcore] var parentRef: Node | Null = null

  private[domcore] val childList: mutable.ArrayBuffer[Node] = mutable.ArrayBuffer.empty

  /** Set exactly once, by whichever `Document.createElement`/`createTextNode`/`createComment` call constructed this
    * node. `null` only for a `Document` itself (a document has no owner document, per spec) or a node built outside the
    * normal creation path (a pathological case a caller should avoid, not one this kernel needs to guard).
    */
  private[domcore] var ownerDocumentRef: Document | Null = null

  /** Backs every reflected attribute accessor the generator auto-implements (`attributeMap.getOrElse`/`.set`), plus
    * `ElementOverrides`'s raw `getAttribute`/`setAttribute`/etc. Declared here (on the shared base every `*Memory`
    * class extends) rather than only on `ElementMemory`, so the SAME field name resolves for every generated
    * reflected-attribute accessor regardless of which interface declared the reflected member — `Text`/`Comment`/ plain
    * `Node` simply never populate it (they have no reflected attributes and no `Element`-level API surface to read it
    * back through).
    */
  private[domcore] val attributeMap: AttributeMap = AttributeMap()

  /** The node's own name — its tag name for an element (`"div"`), or the spec sentinel for the non-element kinds
    * (`"#text"`, `"#comment"`, `"#document"`). A no-arg `*Memory` constructor can't know this on its own, so
    * `ElementFactory`/`Document.createElement`/`createTextNode`/`createComment` stamp it at construction. `Node`'s
    * generic `nodeName` getter (in `NodeOverrides`) reads it; `Element.tagName` returns the same value. Empty until
    * stamped (a node built outside the creation path — a pathological case, not one the kernel guards).
    */
  private[domcore] var nodeNameRef: String = ""

  /** The DOM node-type constant (`Node.ELEMENT_NODE == 1`, `TEXT_NODE == 3`, `COMMENT_NODE == 8`,
    * `DOCUMENT_NODE == 9`), stamped at construction alongside [[nodeNameRef]]. `0` (an invalid node type, no such
    * constant) until stamped — same "built outside the creation path" caveat as [[nodeNameRef]].
    */
  private[domcore] var nodeTypeRef: Int = 0
end NodeMemoryBase
