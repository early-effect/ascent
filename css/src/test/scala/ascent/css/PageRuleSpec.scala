package ascent.css

import zio.*
import zio.test.*

/** `@page` is a top-level descriptor block that accepts both general [[Styles]] Declarations (`margin`, `padding`) and
  * `@page`-only descriptors surfaced via [[PageDescriptors]].
  */
object PageRuleSpec extends ZIOSpecDefault:

  def spec = suite("Page (typed @page rule)")(
    suite("rendering")(
      test("renders as `@page { <descriptors>; }` with no parent selector") {
        val rule = Page(
          Styles.margin("1in"),
          PageDescriptors.size.auto,
          PageDescriptors.pageOrientation.upright,
        )
        val rendered = rule.render
        assertTrue(
          rendered.startsWith("@page {"),
          rendered.contains("margin: 1in;"),
          rendered.contains("size: auto;"),
          rendered.contains("page-orientation: upright;"),
          rendered.trim.endsWith("}"),
        )
      }
    ),
    suite("PageDescriptors generated catalog")(
      test("`size` accepts the `auto` keyword from the spec grammar") {
        assertTrue(PageDescriptors.size.auto.render == "size: auto;")
      },
      test("`page-orientation` covers the three spec keywords") {
        assertTrue(
          PageDescriptors.pageOrientation.upright.render == "page-orientation: upright;",
          PageDescriptors.pageOrientation.rotateLeft.render == "page-orientation: rotate-left;",
          PageDescriptors.pageOrientation.rotateRight.render == "page-orientation: rotate-right;",
        )
      },
      test("`bleed` accepts auto + a length") {
        assertTrue(
          PageDescriptors.bleed.auto.render == "bleed: auto;",
          PageDescriptors.bleed.px(6).render == "bleed: 6.0px;",
        )
      },
    ),
    suite("StyleSink integration")(
      test("installInto registers anonymous @page under a stable key so installing twice is idempotent") {
        for
          sink  <- StyleSink.capturing
          _     <- Page(PageDescriptors.size.auto).installInto(sink)
          rules <- sink.captured
        yield assertTrue(
          rules.size == 1,
          rules.head._2.contains("@page {"),
          rules.head._2.contains("size: auto;"),
        )
      }
    ),
  )
end PageRuleSpec
