package ascent.domgen.aria

import zio.test.*

/** [[AriaRenderer]] turns parsed [[AriaProperty]] entries into `dom-types/.../generated/AriaAttrs.scala`, one
  * `val ariaXxx: AttrKey[T] = AttrKey("aria-xxx", codec)` per property, codec picked from the property's `type` flag.
  */
object AriaRendererSpec extends ZIOSpecDefault:

  def spec = suite("AriaRenderer")(
    test("renders a boolean property as AttrKey[Boolean] with BooleanAsTrueFalse codec") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-pressed", "boolean", Nil, allowUndefined = false)
        )
      )
      assertTrue(
        src.contains("""val ariaPressed: AttrKey[Boolean] = AttrKey("aria-pressed", Codec.BooleanAsTrueFalse)""")
      )
    },
    test("renders an integer property as AttrKey[Int]") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-rowcount", "integer", Nil, allowUndefined = false)
        )
      )
      assertTrue(
        src.contains("""val ariaRowcount: AttrKey[Int] = AttrKey("aria-rowcount", Codec.IntAsString)""")
      )
    },
    test("renders a string-typed property as AttrKey[String]") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-label", "string", Nil, allowUndefined = false)
        )
      )
      assertTrue(
        src.contains("""val ariaLabel: AttrKey[String] = AttrKey("aria-label", Codec.StringAsIs)""")
      )
    },
    test("renders id / idlist / token / tokenlist as AttrKey[String]") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-activedescendant", "id", Nil, allowUndefined = false),
          AriaProperty("aria-controls", "idlist", Nil, allowUndefined = false),
          AriaProperty("aria-live", "token", List("off", "polite", "assertive"), allowUndefined = false),
          AriaProperty("aria-relevant", "tokenlist", List("additions", "all"), allowUndefined = false),
        )
      )
      assertTrue(
        src.contains(
          """val ariaActivedescendant: AttrKey[String] = AttrKey("aria-activedescendant", Codec.StringAsIs)"""
        ),
        src.contains("""val ariaControls: AttrKey[String] = AttrKey("aria-controls", Codec.StringAsIs)"""),
        src.contains("""val ariaLive: AttrKey[String] = AttrKey("aria-live", Codec.StringAsIs)"""),
        src.contains("""val ariaRelevant: AttrKey[String] = AttrKey("aria-relevant", Codec.StringAsIs)"""),
      )
    },
    test("renders a tristate property as AttrKey[Boolean] (common true/false case stays ergonomic)") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-checked", "tristate", Nil, allowUndefined = false)
        )
      )
      assertTrue(
        src.contains("""val ariaChecked: AttrKey[Boolean] = AttrKey("aria-checked", Codec.BooleanAsTrueFalse)""")
      )
    },
    test("camel-cases hyphenated property names: aria-rowindex -> ariaRowindex (not ariaRowIndex)") {
      // Only the leading `aria-` segment is cased; the rest stays one chunk to match the spec name
      // (`aria-rowindex` is single-word) and the hand-written naming.
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-rowindex", "integer", Nil, allowUndefined = false)
        )
      )
      assertTrue(src.contains("ariaRowindex"))
    },
    test("emits a header, package, imports, and `object AriaAttrs` shell") {
      val src = AriaRenderer.render(
        List(
          AriaProperty("aria-label", "string", Nil, allowUndefined = false)
        )
      )
      assertTrue(
        src.contains("AUTO-GENERATED"),
        src.contains("package ascent.domtypes"),
        src.contains("object AriaAttrs:"),
      )
    },
    test("includes a hand-written `role` attribute even though aria-query doesn't list it") {
      // `role` is a semantic role descriptor, not a state, so it's absent from ariaPropsMap.js — but
      // every example app expects `AriaAttrs.role(...)`, so the renderer emits it unconditionally.
      val src = AriaRenderer.render(Nil)
      assertTrue(
        src.contains("""val role: AttrKey[String] = AttrKey("role", Codec.StringAsIs)""")
      )
    },
  )
end AriaRendererSpec
