package ascent.domtypes

/** Converts a typed Scala attribute value to/from its platform-neutral [[AttrValue]] form.
  *
  * HTML attributes serialize differently depending on semantics — most notably booleans, which may be presence-based
  * (`disabled`), `"true"`/`"false"` strings, or `"on"`/`"off"`. A `Codec` captures that per-attribute encoding so the
  * generated attribute keys stay uniform while still producing correct DOM output. Modeled on scala-dom-types' codec
  * concept.
  *
  * Implementations must be **total**: `decode` never throws on malformed or absent input — it falls back to a sensible
  * default. This keeps the DOM-reading path safe.
  */
trait Codec[V]:
  def encode(value: V): AttrValue
  def decode(attr: AttrValue): V

object Codec:

  /** Strings pass through unchanged. An empty string is still a *present* value, not [[AttrValue.Absent]]. */
  val StringAsIs: Codec[String] = new Codec[String]:
    def encode(value: String): AttrValue = AttrValue.Str(value)
    def decode(attr: AttrValue): String  = attr match
      case AttrValue.Str(s)  => s
      case AttrValue.Bool(b) => b.toString
      case AttrValue.Absent  => ""

  /** Ints render as their decimal string; malformed/absent input decodes to `0`. */
  val IntAsString: Codec[Int] = new Codec[Int]:
    def encode(value: Int): AttrValue = AttrValue.Str(value.toString)
    def decode(attr: AttrValue): Int  = attr match
      case AttrValue.Str(s) => s.toIntOption.getOrElse(0)
      case _                => 0

  /** Doubles render via `toString`; malformed/absent input decodes to `0.0`. */
  val DoubleAsString: Codec[Double] = new Codec[Double]:
    def encode(value: Double): AttrValue = AttrValue.Str(value.toString)
    def decode(attr: AttrValue): Double  = attr match
      case AttrValue.Str(s) => s.toDoubleOption.getOrElse(0.0)
      case _                => 0.0

  /** Presence-based boolean: `true` → present empty attribute, `false` → absent (removed). Any present value decodes to
    * `true`. This is the encoding for attributes like `disabled`, `required`, `hidden`.
    */
  val BooleanAsAttrPresence: Codec[Boolean] = new Codec[Boolean]:
    def encode(value: Boolean): AttrValue = if value then AttrValue.Str("") else AttrValue.Absent
    def decode(attr: AttrValue): Boolean  = attr match
      case AttrValue.Str(_)  => true
      case AttrValue.Bool(b) => b
      case AttrValue.Absent  => false

  /** Boolean serialized as the literal strings `"true"`/`"false"` (e.g. `contenteditable`, `spellcheck`, `draggable`,
    * `aria-*`). Only `"true"` decodes to `true`.
    */
  val BooleanAsTrueFalse: Codec[Boolean] = new Codec[Boolean]:
    def encode(value: Boolean): AttrValue = AttrValue.Str(if value then "true" else "false")
    def decode(attr: AttrValue): Boolean  = attr match
      case AttrValue.Str(s)  => s == "true"
      case AttrValue.Bool(b) => b
      case AttrValue.Absent  => false

  /** Boolean serialized as `"on"`/`"off"` (e.g. `autocomplete`). Only `"on"` decodes to `true`. */
  val BooleanAsOnOff: Codec[Boolean] = new Codec[Boolean]:
    def encode(value: Boolean): AttrValue = AttrValue.Str(if value then "on" else "off")
    def decode(attr: AttrValue): Boolean  = attr match
      case AttrValue.Str(s)  => s == "on"
      case AttrValue.Bool(b) => b
      case AttrValue.Absent  => false
end Codec
