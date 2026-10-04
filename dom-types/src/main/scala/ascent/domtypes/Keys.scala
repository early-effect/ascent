package ascent.domtypes

/** A non-void DOM element key (`div`, `span`, `button`). Carries the canonical `domName`. Its DSL constructor accepts
  * both attributes and children. `E` is the element's phantom marker (`ascent.domtypes.tags`), so an attribute
  * introduced on a different element is rejected at the call site.
  */
final case class ElementKey[E <: ascent.domtypes.tags.Element](domName: String)

/** A void DOM element key (`br`, `input`, `img`): elements that may have no children per the HTML spec. Distinct from
  * [[ElementKey]] so the DSL constructor can accept attribute/event args only, rejecting children at compile time.
  */
final case class VoidElementKey[E <: ascent.domtypes.tags.Element](domName: String)

/** A DOM attribute key, parameterised by the Scala value type the user supplies and the element marker that introduces
  * the content attribute.
  *
  * The [[Codec]] converts that typed value into a platform-neutral [[AttrValue]] for the DOM backend, capturing the
  * per-attribute serialization rules (presence-flag booleans, integer stringification, etc.) so the DSL stays uniform
  * across attribute kinds. `E` is invariant: the contravariance that lets `id` (typed at `Element`) apply to a `div`
  * lives on the DSL arg, not on this key.
  */
final case class AttrKey[V, E <: ascent.domtypes.tags.Element](domName: String, codec: Codec[V]):
  /** Encode a typed value into the platform-neutral form the DOM backend writes. */
  def encode(value: V): AttrValue = codec.encode(value)

/** A DOM event key.
  *
  * The event interface type is carried as a STRING (e.g. `"ascent.dom.PointerEvent"`) rather than a real type so this
  * file remains platform-neutral and `dom-types` has zero facade dependency. The js-side typed event DSL resolves the
  * string to the real `@js.native` facade at the binding boundary — the cross-platform trick scala-dom-types/Laminar
  * use.
  */
final case class EventKey(domName: String, eventTypeString: String)
