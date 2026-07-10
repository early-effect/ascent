package ascent.css

import zio.*
import zio.test.*

/** `@counter-style` is a top-level named descriptor block:
  * `@counter-style my-counter { system: numeric; symbols: "0"; }`.
  */
object CounterStyleRuleSpec extends ZIOSpecDefault:

  def spec = suite("CounterStyle (typed @counter-style rule)")(
    suite("rendering")(
      test("renders as `@counter-style <name> { <descriptors>; }`") {
        val rule = CounterStyle(
          "my-counter",
          CounterStyleDescriptors.system.numeric,
          CounterStyleDescriptors.symbols("\"0\" \"1\" \"2\""),
          CounterStyleDescriptors.suffix(". "),
        )
        val rendered = rule.render
        assertTrue(
          rendered.startsWith("@counter-style my-counter {"),
          rendered.contains("system: numeric;"),
          rendered.contains("symbols: \"0\" \"1\" \"2\";"),
          rendered.contains("suffix: . ;"),
          rendered.trim.endsWith("}"),
        )
      }
    ),
    suite("CounterStyleDescriptors generated catalog")(
      test("`system` covers the spec keywords (cyclic / numeric / alphabetic / symbolic / additive)") {
        assertTrue(
          CounterStyleDescriptors.system.cyclic.render == "system: cyclic;",
          CounterStyleDescriptors.system.numeric.render == "system: numeric;",
          CounterStyleDescriptors.system.alphabetic.render == "system: alphabetic;",
          CounterStyleDescriptors.system.symbolic.render == "system: symbolic;",
          CounterStyleDescriptors.system.additive.render == "system: additive;",
        )
      },
      test(
        "`speak-as` is reached via Styles (it's also a general CSS Speech property — only one canonical typed builder)"
      ) {
        assertTrue(
          Styles.speakAs.spellOut.render == "speak-as: spell-out;",
          Styles.speakAs.apply("bullets").render == "speak-as: bullets;",
        )
      },
    ),
    suite("StyleSink integration")(
      test("installInto registers @counter-style under a key derived from the rule name") {
        for
          sink  <- StyleSink.capturing
          _     <- CounterStyle("thumbs", CounterStyleDescriptors.system.cyclic).installInto(sink)
          rules <- sink.captured
        yield assertTrue(
          rules.size == 1,
          rules.head._1.contains("thumbs"),
          rules.head._2.contains("@counter-style thumbs {"),
        )
      },
      test("two distinct counter-style names coexist; the same name twice is idempotent") {
        for
          sink  <- StyleSink.capturing
          _     <- CounterStyle("a", CounterStyleDescriptors.system.numeric).installInto(sink)
          _     <- CounterStyle("b", CounterStyleDescriptors.system.cyclic).installInto(sink)
          _     <- CounterStyle("a", CounterStyleDescriptors.system.alphabetic).installInto(sink)
          rules <- sink.captured
        yield assertTrue(
          rules.size == 2,
          rules.exists((_, body) => body.contains("system: alphabetic;")),
          !rules.exists((_, body) => body.contains("system: numeric;")),
        )
      },
    ),
  )
end CounterStyleRuleSpec
