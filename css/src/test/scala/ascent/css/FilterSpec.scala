package ascent.css

import zio.test.*

/** The [[Filter]] value type — typed `filter` / `backdrop-filter` functions (`blur`, `brightness`, `saturate`, …)
  * composed from [[Length]], [[Angle]], and plain numbers, with a space-joined multi-filter form ([[Filter.list]]).
  */
object FilterSpec extends ZIOSpecDefault:

  def spec = suite("Filter")(
    test("blur takes a Length radius") {
      assertTrue(Filter.blur(Length.px(24)).render == "blur(24.0px)")
    },
    test("brightness takes a unitless multiplier (platform-stable)") {
      assertTrue(
        Filter.brightness(1).render == "brightness(1.0)",
        Filter.brightness(1.08).render == "brightness(1.08)",
      )
    },
    test("saturate takes a unitless multiplier (1.4 ≡ 140%)") {
      assertTrue(Filter.saturate(1.4).render == "saturate(1.4)")
    },
    test("Filter.list space-joins functions (the glass backdrop-filter)") {
      assertTrue(
        Filter.list(Filter.blur(Length.px(24)), Filter.saturate(1.4)).render == "blur(24.0px) saturate(1.4)"
      )
    },
    test("a Filter attaches to filter / backdrop-filter via the universal overload") {
      assertTrue(Styles.filter(Filter.brightness(1.08)).render == "filter: brightness(1.08);")
    },
  )
end FilterSpec
