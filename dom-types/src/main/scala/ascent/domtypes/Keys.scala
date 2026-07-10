package ascent.domtypes

/** A non-void DOM element key (`div`, `span`, `button`, …). Carries the canonical `domName`. Its DSL constructor
  * accepts both attributes and children.
  */
final case class ElementKey(domName: String)

/** A void DOM element key (`br`, `input`, `img`, …) — elements that may have no children per the HTML spec. Distinct
  * from [[ElementKey]] so the DSL constructor can accept attribute/event args only, rejecting children at compile time.
  */
final case class VoidElementKey(domName: String)

/** A DOM attribute key, parameterised by the Scala value type the user supplies.
  *
  * The [[Codec]] converts that typed value into a platform-neutral [[AttrValue]] for the DOM backend — capturing the
  * per-attribute serialization rules (presence-flag booleans, integer stringification, etc.) so the DSL stays uniform
  * across attribute kinds.
  */
final case class AttrKey[V](domName: String, codec: Codec[V]):
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
