package ascent.domtypes

import zio.test.*

object KeysSpec extends ZIOSpecDefault:

  def spec = suite("DOM key types")(
    suite("ElementKey / VoidElementKey")(
      test("ElementKey carries the domName the renderer emits") {
        val k = ElementKey("div")
        assertTrue(k.domName == "div")
      },
      test("VoidElementKey is a distinct type carrying its domName (void/non-void encoded by type)") {
        val k = VoidElementKey("input")
        assertTrue(k.domName == "input")
      },
      test("equality is structural so duplicate keys collapse in sets/maps") {
        assertTrue(
          ElementKey("div") == ElementKey("div"),
          VoidElementKey("br") == VoidElementKey("br"),
        )
      },
    ),
    suite("AttrKey[V]")(
      test("encodes a typed value via its codec to produce a present AttrValue") {
        val k = AttrKey[Int]("tabIndex", Codec.IntAsString)
        assertTrue(k.encode(42) == AttrValue.Str("42"))
      },
      test("a presence-coded boolean attr encodes false to Absent (attribute removed)") {
        val k = AttrKey[Boolean]("required", Codec.BooleanAsAttrPresence)
        assertTrue(k.encode(false) == AttrValue.Absent, k.encode(true) == AttrValue.Str(""))
      },
      test("the same name + codec compare equal regardless of how they were constructed") {
        // The renderer re-emits identical AttrKey vals across regen runs, so equality must be structural.
        val a = AttrKey[String]("class", Codec.StringAsIs)
        val b = AttrKey[String]("class", Codec.StringAsIs)
        assertTrue(a == b)
      },
    ),
    suite("EventKey")(
      test("carries the dom event name and the platform-neutral facade type STRING") {
        val k = EventKey("click", "ascent.dom.PointerEvent")
        assertTrue(k.domName == "click", k.eventTypeString == "ascent.dom.PointerEvent")
      },
      test("structural equality across constructions") {
        assertTrue(EventKey("input", "ascent.dom.InputEvent") == EventKey("input", "ascent.dom.InputEvent"))
      },
    ),
  )
end KeysSpec
