package ascent.css

import zio.test.*

/** The Styles property catalog: typed CSS property objects (`object foo extends DS("foo") with <ValueTraits>`) that
  * produce [[Declaration]]s via a generic `apply(String)` escape hatch or typed value-grammar mixins (Length, Color,
  * Auto, …).
  */
object StylesSpec extends ZIOSpecDefault:

  import Styles.*

  def spec = suite("Styles")(
    suite("the universal Declaration form (apply(String))")(
      test("any property accepts a raw string and renders as `name: value;`") {
        assertTrue(color("rebeccapurple").render == "color: rebeccapurple;")
      }
    ),
    suite("Length value-grammar")(
      test("Length: px / em / rem / pt / cm / mm / in / pc / Q / ex / ch / vh / vw / vmin / vmax") {
        assertTrue(
          fontSize.px(24).render == "font-size: 24.0px;",
          fontSize.em(1.5).render == "font-size: 1.5em;",
          fontSize.rem(0.875).render == "font-size: 0.875rem;",
          width.vh(50.0).render == "width: 50.0vh;",
          height.vw(100.0).render == "height: 100.0vw;",
        )
      },
      test("Length.zero is the unitless `0`") {
        assertTrue(padding.zero.render == "padding: 0;")
      },
    ),
    suite("Percent")(
      test("Percent: pct(50.0) renders as `50.0%`") {
        assertTrue(width.pct(50).render == "width: 50.0%;")
      }
    ),
    suite("Auto / None / Normal keyword traits")(
      test("Auto.auto") { assertTrue(margin.auto.render == "margin: auto;") },
      test("None.none") { assertTrue(textDecoration.none.render == "text-decoration: none;") },
      test("Normal.normal (line-height accepts normal)") {
        assertTrue(lineHeight.normal.render == "line-height: normal;")
      },
    ),
    suite("CSS-wide cascade keywords (on every property via DS)")(
      test("inherit / initial / unset / revert / revertLayer on an arbitrary property") {
        assertTrue(
          color.inherit.render == "color: inherit;",
          display.initial.render == "display: initial;",
          margin.unset.render == "margin: unset;",
          width.revert.render == "width: revert;",
          fontSize.revertLayer.render == "font-size: revert-layer;",
        )
      },
      test("they're available on bare properties too (background is a bare DS with no value traits)") {
        assertTrue(background.inherit.render == "background: inherit;")
      },
    ),
    suite("Value-ADT mixins — typed builders discoverable on the property")(
      // The varargs forms widen to CssValue before re-applying; a regression there recurses (StackOverflow), so pin
      // both the single-fn and multi-fn paths.
      test("transform.translateY / multi-function via apply(Transform*)") {
        assertTrue(
          transform.translateY(Length.px(8)).render == "transform: translateY(8.0px);",
          transform(Transform.rotate(Angle.deg(90)), Transform.scale(1.1)).render
            == "transform: rotate(90.0deg) scale(1.1);",
        )
      },
      test("filter.blur / multi-function via apply(Filter*)") {
        assertTrue(
          filter.blur(Length.px(4)).render == "filter: blur(4.0px);",
          filter(Filter.blur(Length.px(24)), Filter.saturate(1.4)).render == "filter: blur(24.0px) saturate(1.4);",
        )
      },
      test("boxShadow(Shadow*) comma-joins layers") {
        assertTrue(
          boxShadow(
            Shadow(Length.zero, Length.zero, Length.px(12), Color.hex("#ff00aa"))
          ).render == "box-shadow: 0 0 12.0px rgb(255, 0, 170);"
        )
      },
      test("backgroundImage(gradient) attaches a typed gradient") {
        val g = Gradient.linear(Angle.deg(90))(ColorStop(Color.transparent))
        assertTrue(backgroundImage(g).render == "background-image: linear-gradient(90.0deg, transparent);")
      },
    ),
    suite("Color")(
      test("color via a string keyword") {
        assertTrue(color("white").render == "color: white;")
      },
      test("color.rgb(r,g,b) renders the CSS Color 4 comma form when opaque") {
        assertTrue(color.rgb(0, 0, 0).render == "color: rgb(0, 0, 0);")
      },
      test("color.rgba(r,g,b,a) renders the modern slash-alpha form") {
        assertTrue(color.rgba(255, 255, 255, 0.5).render == "color: rgb(255 255 255 / 0.5);")
      },
    ),
    suite("Display, Position, Cursor — keyword properties")(
      test("display.flex / .block / .none / .inline / .inlineBlock") {
        assertTrue(
          display.flex.render == "display: flex;",
          display.block.render == "display: block;",
          display.none.render == "display: none;",
          display.inline.render == "display: inline;",
          display.inlineBlock.render == "display: inline-block;",
        )
      },
      test("position.relative / .absolute / .fixed / .static / .sticky") {
        assertTrue(
          position.relative.render == "position: relative;",
          position.absolute.render == "position: absolute;",
          position.fixed.render == "position: fixed;",
          position.static.render == "position: static;",
          position.sticky.render == "position: sticky;",
        )
      },
      test("cursor.pointer / .default / .text / .notAllowed") {
        assertTrue(
          cursor.pointer.render == "cursor: pointer;",
          cursor.default.render == "cursor: default;",
          cursor.text.render == "cursor: text;",
          cursor.notAllowed.render == "cursor: not-allowed;",
        )
      },
    ),
    suite("Flex — alignItems, justifyContent, flexDirection")(
      test("alignItems.center / .flexStart / .flexEnd / .stretch / .baseline") {
        assertTrue(
          alignItems.center.render == "align-items: center;",
          alignItems.flexStart.render == "align-items: flex-start;",
          alignItems.flexEnd.render == "align-items: flex-end;",
          alignItems.stretch.render == "align-items: stretch;",
          alignItems.baseline.render == "align-items: baseline;",
        )
      },
      test("justifyContent.center / .spaceBetween / .spaceAround / .flexEnd") {
        assertTrue(
          justifyContent.center.render == "justify-content: center;",
          justifyContent.spaceBetween.render == "justify-content: space-between;",
          justifyContent.spaceAround.render == "justify-content: space-around;",
          justifyContent.flexEnd.render == "justify-content: flex-end;",
        )
      },
      test("flexDirection.row / .column / .rowReverse / .columnReverse") {
        assertTrue(
          flexDirection.row.render == "flex-direction: row;",
          flexDirection.column.render == "flex-direction: column;",
          flexDirection.rowReverse.render == "flex-direction: row-reverse;",
          flexDirection.columnReverse.render == "flex-direction: column-reverse;",
        )
      },
    ),
    suite("Numbers and ints")(
      test("zIndex accepts an Int") {
        assertTrue(zIndex(5).render == "z-index: 5;")
      },
      test("opacity accepts a Double") {
        assertTrue(opacity(0.5).render == "opacity: 0.5;")
      },
      test("flexGrow accepts a Double") {
        assertTrue(flexGrow(1.0).render == "flex-grow: 1.0;")
      },
    ),
    suite("Margin / Padding shorthand variants — typed only")(
      test("typed numeric shorthand: margin(0, 14.px, 0, 20.px) — bare 0 + numeric extensions") {
        assertTrue(
          margin(0, 14.px, 0, 20.px).render == "margin: 0 14.0px 0 20.0px;",
          padding(8.px, 16.px).render == "padding: 8.0px 16.0px;",
          margin(0, 4.px).render == "margin: 0 4.0px;",
        )
      },
      test("keyword sides via Length.auto — margin(0, Length.auto) (no String overload)") {
        assertTrue(margin(0, Length.auto).render == "margin: 0 auto;")
      },
    ),
    suite("Sanity smoke-test on TodoMVC-shape rules")(
      test("a typical TodoMVC rule composes from multiple properties cleanly") {
        val rule = Selector(
          ".new-todo",
          position.relative,
          margin.zero,
          width.pct(100),
          fontSize.px(24),
          padding(16.px, 16.px, 16.px, 60.px),
          border("none"),
          background("rgba(0, 0, 0, 0.003)"),
          boxShadow("inset 0 -2px 1px rgba(0,0,0,0.03)"),
        )
        val rendered = rule.render
        assertTrue(
          rendered.contains(".new-todo {"),
          rendered.contains("position: relative;"),
          rendered.contains("margin: 0;"),
          rendered.contains("width: 100.0%;"),
          rendered.contains("font-size: 24.0px;"),
          rendered.contains("padding: 16.0px 16.0px 16.0px 60.0px;"),
          rendered.contains("border: none;"),
        )
      }
    ),
  )
end StylesSpec
