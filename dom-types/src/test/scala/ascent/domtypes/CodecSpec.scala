package ascent.domtypes

import zio.test.*

object CodecSpec extends ZIOSpecDefault:

  def spec = suite("Codec")(
    suite("StringAsIs")(
      test("encode passes the string through to a present attribute value") {
        assertTrue(Codec.StringAsIs.encode("hello") == AttrValue.Str("hello"))
      },
      test("encode of empty string is still a present (empty) value, not Absent") {
        assertTrue(Codec.StringAsIs.encode("") == AttrValue.Str(""))
      },
      test("decode reads the string back") {
        assertTrue(Codec.StringAsIs.decode(AttrValue.Str("hi")) == "hi")
      },
      test("decode of Absent yields the empty string") {
        assertTrue(Codec.StringAsIs.decode(AttrValue.Absent) == "")
      },
      test("round-trips arbitrary strings") {
        check(Gen.string) { s =>
          assertTrue(Codec.StringAsIs.decode(Codec.StringAsIs.encode(s)) == s)
        }
      },
    ),
    suite("IntAsString")(
      test("encode renders the int as a string value") {
        assertTrue(Codec.IntAsString.encode(42) == AttrValue.Str("42"))
      },
      test("encode handles negative values") {
        assertTrue(Codec.IntAsString.encode(-7) == AttrValue.Str("-7"))
      },
      test("decode parses a numeric string") {
        assertTrue(Codec.IntAsString.decode(AttrValue.Str("123")) == 123)
      },
      test("decode of malformed input falls back to 0 rather than throwing") {
        assertTrue(Codec.IntAsString.decode(AttrValue.Str("not-a-number")) == 0)
      },
      test("decode of Absent yields 0") {
        assertTrue(Codec.IntAsString.decode(AttrValue.Absent) == 0)
      },
      test("round-trips arbitrary ints") {
        check(Gen.int) { i =>
          assertTrue(Codec.IntAsString.decode(Codec.IntAsString.encode(i)) == i)
        }
      },
    ),
    suite("DoubleAsString")(
      test("encode renders the double as a string value") {
        assertTrue(Codec.DoubleAsString.encode(1.5) == AttrValue.Str("1.5"))
      },
      test("decode of malformed input falls back to 0.0") {
        assertTrue(Codec.DoubleAsString.decode(AttrValue.Str("xyz")) == 0.0)
      },
      test("round-trips finite doubles") {
        check(Gen.double(-1e9, 1e9)) { d =>
          assertTrue(Codec.DoubleAsString.decode(Codec.DoubleAsString.encode(d)) == d)
        }
      },
    ),
    suite("BooleanAsAttrPresence")(
      test("true encodes as a present empty attribute") {
        assertTrue(Codec.BooleanAsAttrPresence.encode(true) == AttrValue.Str(""))
      },
      test("false encodes as Absent (attribute removed)") {
        assertTrue(Codec.BooleanAsAttrPresence.encode(false) == AttrValue.Absent)
      },
      test("decode treats any present value as true") {
        assertTrue(Codec.BooleanAsAttrPresence.decode(AttrValue.Str("")) == true) &&
        assertTrue(Codec.BooleanAsAttrPresence.decode(AttrValue.Str("disabled")) == true)
      },
      test("decode treats Absent as false") {
        assertTrue(Codec.BooleanAsAttrPresence.decode(AttrValue.Absent) == false)
      },
    ),
    suite("BooleanAsTrueFalse")(
      test("true/false encode to the literal strings") {
        assertTrue(Codec.BooleanAsTrueFalse.encode(true) == AttrValue.Str("true")) &&
        assertTrue(Codec.BooleanAsTrueFalse.encode(false) == AttrValue.Str("false"))
      },
      test("decode reads 'true' as true and anything else as false") {
        assertTrue(Codec.BooleanAsTrueFalse.decode(AttrValue.Str("true")) == true) &&
        assertTrue(Codec.BooleanAsTrueFalse.decode(AttrValue.Str("false")) == false) &&
        assertTrue(Codec.BooleanAsTrueFalse.decode(AttrValue.Absent) == false)
      },
    ),
    suite("BooleanAsOnOff")(
      test("true/false encode to on/off") {
        assertTrue(Codec.BooleanAsOnOff.encode(true) == AttrValue.Str("on")) &&
        assertTrue(Codec.BooleanAsOnOff.encode(false) == AttrValue.Str("off"))
      },
      test("decode reads 'on' as true, otherwise false") {
        assertTrue(Codec.BooleanAsOnOff.decode(AttrValue.Str("on")) == true) &&
        assertTrue(Codec.BooleanAsOnOff.decode(AttrValue.Str("off")) == false)
      },
    ),
  )
end CodecSpec
