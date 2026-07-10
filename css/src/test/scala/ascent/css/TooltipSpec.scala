package ascent.css

import ascent.ast.Attr
import ascent.domtypes.AttrValue
import zio.test.*

/** [[Tooltip]] — a pure-CSS tooltip primitive. The Scala API takes a label + optional position and returns the
  * [[Attr]]s to attach to the target; the visual is driven by the global [[Tooltip.styles]] CssClass. The `aria-label`
  * mirrors the visual label as a single source of truth, and the bubble reveals on `:hover` AND `:focus-visible` for
  * keyboard users.
  */
object TooltipSpec extends ZIOSpecDefault:

  def spec = suite("Tooltip")(
    suite("attribute production")(
      test("Tooltip(text) produces a class attr, a data-tooltip attr, and an aria-label attr") {
        val attrs  = Tooltip("Delete todo")
        val byName = attrs.collect { case Attr.StaticAttr(name, value) => name -> value }.toMap
        assertTrue(
          byName.contains("class"),
          byName("data-tooltip") == AttrValue.Str("Delete todo"),
          byName("aria-label") == AttrValue.Str("Delete todo"),
        )
      },
      test("Tooltip's class attr carries the Tooltip.styles className") {
        val attrs = Tooltip("Delete")
        attrs.collect { case Attr.StaticAttr("class", AttrValue.Str(s)) => s }.headOption match
          case Some(cls) => assertTrue(cls == Tooltip.styles.className)
          case None      => assertNever("expected class attr")
      },
      test("a Tooltip with explicit position adds a data-tooltip-position attr") {
        val attrs  = Tooltip("Edit", Tooltip.Position.Bottom)
        val byName = attrs.collect { case Attr.StaticAttr(name, value) => name -> value }.toMap
        assertTrue(byName("data-tooltip-position") == AttrValue.Str("bottom"))
      },
      test("default position omits the data-tooltip-position attr (top is the CSS default)") {
        val attrs = Tooltip("Edit")
        val names = attrs.collect { case Attr.StaticAttr(name, _) => name }.toSet
        assertTrue(!names.contains("data-tooltip-position"))
      },
      test("every Position enum value renders to a stable lower-case string the CSS selectors depend on") {
        assertTrue(
          Tooltip.Position.Top.render == "top",
          Tooltip.Position.Bottom.render == "bottom",
          Tooltip.Position.Left.render == "left",
          Tooltip.Position.Right.render == "right",
        )
      },
    ),
    suite("CSS body")(
      test("the styles CssClass renders rules for ::after, ::before, :hover, and :focus-visible") {
        val css = Tooltip.styles.renderCss
        assertTrue(
          css.contains("::after"),
          css.contains("::before"),
          css.contains(":hover::after"),
          css.contains(":focus-visible::after"),
          css.contains("attr(data-tooltip)"),
        )
      },
      test("position-specific rules exist for bottom/left/right (top is the default)") {
        val css = Tooltip.styles.renderCss
        assertTrue(
          css.contains("data-tooltip-position='bottom'"),
          css.contains("data-tooltip-position='left'"),
          css.contains("data-tooltip-position='right'"),
        )
      },
      test("the bubble respects prefers-reduced-motion by disabling its transition") {
        val css = Tooltip.styles.renderCss
        assertTrue(css.contains("@media (prefers-reduced-motion: reduce)"))
      },
    ),
  )
end TooltipSpec
