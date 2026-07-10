package ascent.domtypes

import zio.test.*

/** ARIA attribute keys — hand-written because webref's IDL doesn't surface ARIA as element properties. */
object AriaAttrsSpec extends ZIOSpecDefault:

  def spec = suite("AriaAttrs")(
    test("ariaLabel maps to the dom name `aria-label` with a string codec") {
      assertTrue(
        AriaAttrs.ariaLabel.domName == "aria-label",
        AriaAttrs.ariaLabel.encode("Delete todo") == AttrValue.Str("Delete todo"),
      )
    },
    test("ariaPressed is a boolean encoded as `true` / `false` (NOT presence)") {
      // ARIA booleans use literal "true"/"false", not the HTML presence-means-true convention.
      assertTrue(
        AriaAttrs.ariaPressed.domName == "aria-pressed",
        AriaAttrs.ariaPressed.encode(true) == AttrValue.Str("true"),
        AriaAttrs.ariaPressed.encode(false) == AttrValue.Str("false"),
      )
    },
    test("ariaHidden is a string-encoded boolean (`'true'` / `'false'`)") {
      assertTrue(AriaAttrs.ariaHidden.encode(true) == AttrValue.Str("true"))
    },
    test("ariaLive accepts the canonical 'off' / 'polite' / 'assertive' string values") {
      assertTrue(
        AriaAttrs.ariaLive.encode("polite") == AttrValue.Str("polite")
      )
    },
    test("role is ALSO exposed here (not strictly aria but the same authoring concern)") {
      assertTrue(
        AriaAttrs.role.domName == "role",
        AriaAttrs.role.encode("status") == AttrValue.Str("status"),
      )
    },
    test("the full set covers labelledby/describedby/atomic/relevant/checked/expanded/disabled/current") {
      val names = List(
        AriaAttrs.ariaLabel,
        AriaAttrs.ariaLabelledby,
        AriaAttrs.ariaDescribedby,
        AriaAttrs.ariaPressed,
        AriaAttrs.ariaHidden,
        AriaAttrs.ariaLive,
        AriaAttrs.ariaAtomic,
        AriaAttrs.ariaRelevant,
        AriaAttrs.ariaChecked,
        AriaAttrs.ariaExpanded,
        AriaAttrs.ariaDisabled,
        AriaAttrs.ariaCurrent,
      ).map(_.domName)
      assertTrue(names.distinct.size == names.size, names.forall(_.startsWith("aria-")))
    },
  )
end AriaAttrsSpec
