package ascent.domcore

/** Converts a reflected attribute's typed Scala value to/from the raw String an [[AttributeMap]] actually stores.
  * Closed on purpose — every `[Reflect]`-marked WebIDL attribute the generator's reflection path can produce is one of
  * these four primitive shapes (String/Boolean/Int/Double); a reflected attribute is never enum- or interface-typed per
  * spec (see `ascent.domgen.Webref.IdlAttribute.reflected`'s scaladoc), so there is no case this needs to stay open
  * for.
  *
  * `Boolean` is presence-coded (HTML boolean-attribute semantics: ANY stored value means `true`; the attribute being
  * absent — not represented by this codec, but by [[AttributeMap]]'s own storage — means `false`), so its `encode` only
  * matters for the `true` case; [[AttributeMap.set]] special-cases `false` to remove the attribute entirely rather than
  * calling `encode`.
  */
sealed trait AttrCodec[V]:
  def encode(value: V): String
  def decode(raw: String): V

object AttrCodec:
  given AttrCodec[String] with
    def encode(value: String): String = value
    def decode(raw: String): String   = raw

  given AttrCodec[Boolean] with
    def encode(value: Boolean): String = ""
    def decode(raw: String): Boolean   = true // any stored value (including "") means present

  given AttrCodec[Int] with
    def encode(value: Int): String = value.toString
    def decode(raw: String): Int   = raw.toIntOption.getOrElse(0)

  given AttrCodec[Double] with
    def encode(value: Double): String = value.toString
    def decode(raw: String): Double   = raw.toDoubleOption.getOrElse(0.0)
end AttrCodec
