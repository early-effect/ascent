package ascent.css

import ascent.ast.Attr
import ascent.css.Styles.*
import ascent.domtypes.AttrValue

/** A pure-CSS tooltip primitive — no JS, no popper, no portal. The visual is driven by pseudo-elements (`::before` for
  * the arrow, `::after` for the bubble) on the target element, revealed on `:hover` AND `:focus-visible` (so keyboard
  * users see them).
  *
  * **Usage** (on any element):
  * {{{
  *   Elements.button(Tooltip("Delete todo")*, "×")            // top by default
  *   Elements.button(Tooltip("Toggle all", Tooltip.Position.Right)*, "❯")
  * }}}
  *
  * The factory returns a `List[Attr]` so it splats cleanly into element constructors.
  *
  * **Accessibility**: the same string is wired to `aria-label`, so screen readers announce it correctly even though the
  * visual bubble is `aria-hidden`. The reveal triggers include `:focus-visible` so keyboard navigation surfaces
  * tooltips too.
  *
  * The tooltip CSS rides along with the attributes [[apply]] returns, so it reaches any render that uses a tooltip — no
  * separate install step.
  */
object Tooltip:

  /** Where the tooltip bubble appears, relative to the target. Top is the default. */
  enum Position:
    case Top, Bottom, Left, Right

    def render: String = this match
      case Top    => "top"
      case Bottom => "bottom"
      case Left   => "left"
      case Right  => "right"
  end Position

  /** Build the attribute list for a target element.
    *
    * Returns `class`, `data-tooltip`, `aria-label`, and (for non-default positions) `data-tooltip-position`. Splat into
    * an element constructor with `*`:
    * {{{
    *   Elements.button(Tooltip("Delete")*, ...)
    * }}}
    */
  def apply(text: String, position: Position = Position.Top): List[Attr[Any]] =
    val base = List(
      Attr.StaticAttr("class", AttrValue.Str(styles.className)),
      // Contribute the tooltip CSS to the render (this factory bypasses cssClassToArg, so it must carry the blocks
      // itself — otherwise a mount would style no tooltips).
      Attr.Style(styles.contributionBlocks),
      Attr.StaticAttr("data-tooltip", AttrValue.Str(text)),
      Attr.StaticAttr("aria-label", AttrValue.Str(text)),
    )
    if position == Position.Top then base
    else base :+ Attr.StaticAttr("data-tooltip-position", AttrValue.Str(position.render))
  end apply

  // --- styling ---

  // Design tokens — kept inside Tooltip so the visual is self-contained. Callers can
  // theme by overriding the CssClass body in their own stylesheet if they want.
  private val bubbleBg     = "rgba(10, 4, 24, 0.96)"
  private val bubbleColor  = "#f6e7ff"
  private val bubbleBorder = "rgba(255, 0, 170, 0.4)"
  private val bubbleGlow   = "0 4px 16px rgba(0,0,0,0.5), 0 0 12px rgba(255,0,170,0.25)"

  /** Fade + slight slide-in for the tooltip bubble — keyed off the target's hover/focus. */
  object FadeIn
      extends Keyframes(
        "ascent-tooltip-fade-in",
        Frame
          .from(opacity(0.0), Declaration("transform", "translate(var(--tt-x, -50%), var(--tt-y, calc(-100% - 4px)))")),
        Frame
          .to(opacity(1.0), Declaration("transform", "translate(var(--tt-x, -50%), var(--tt-y, calc(-100% - 12px)))")),
      )

  /** The single CssClass that powers every tooltip. Add the className to a target via the [[apply]] factory; the
    * pseudo-elements + reveal triggers do the rest.
    */
  object styles
      extends CssClass(
        position.relative,

        // The bubble (::after) — hidden by default, revealed on hover/focus-visible. Position
        // is set with absolute coords + a CSS variable transform so per-position rules can
        // override `--tt-x` / `--tt-y` to reposition. Top is the default: bubble centred above.
        Selector(
          "::after",
          Declaration("content", "attr(data-tooltip)"),
          Declaration("position", "absolute"),
          Declaration("left", "50%"),
          Declaration("bottom", "100%"),
          Declaration("transform", "translate(-50%, -4px)"),
          Declaration("padding", "6px 10px"),
          Declaration("background", bubbleBg),
          Declaration("color", bubbleColor),
          Declaration("font-size", "12px"),
          Declaration("font-weight", "500"),
          Declaration("white-space", "nowrap"),
          Declaration("border", s"1px solid $bubbleBorder"),
          Declaration("border-radius", "6px"),
          Declaration("box-shadow", bubbleGlow),
          Declaration("letter-spacing", "0.3px"),
          Declaration("text-transform", "none"),
          Declaration("pointer-events", "none"),
          Declaration("opacity", "0"),
          Declaration("transition", "opacity 0.18s ease, transform 0.18s ease"),
          Declaration("z-index", "1000"),
          Declaration("aria-hidden", "true"),
        ),

        // The arrow (::before) — small triangle pointing at the target. Same pseudo-element
        // strategy but rendered via borders.
        Selector(
          "::before",
          Declaration("content", "''"),
          Declaration("position", "absolute"),
          Declaration("left", "50%"),
          Declaration("bottom", "100%"),
          Declaration("transform", "translate(-50%, 0)"),
          Declaration("border", "5px solid transparent"),
          Declaration("border-top-color", bubbleBg),
          Declaration("pointer-events", "none"),
          Declaration("opacity", "0"),
          Declaration("transition", "opacity 0.18s ease"),
          Declaration("z-index", "1000"),
        ),

        // Reveal: hover OR focus-visible (keyboard users). Both pseudo-elements fade in.
        Selector(":hover::after", Declaration("opacity", "1"), Declaration("transform", "translate(-50%, -12px)")),
        Selector(":hover::before", Declaration("opacity", "1")),
        Selector(
          ":focus-visible::after",
          Declaration("opacity", "1"),
          Declaration("transform", "translate(-50%, -12px)"),
        ),
        Selector(":focus-visible::before", Declaration("opacity", "1")),

        // --- position variants ---

        // bottom: bubble below, arrow points up.
        Selector(
          "[data-tooltip-position='bottom']::after",
          Declaration("bottom", "auto"),
          Declaration("top", "100%"),
          Declaration("transform", "translate(-50%, 4px)"),
        ),
        Selector(
          "[data-tooltip-position='bottom']::before",
          Declaration("bottom", "auto"),
          Declaration("top", "100%"),
          Declaration("transform", "translate(-50%, -100%)"),
          Declaration("border", "5px solid transparent"),
          Declaration("border-bottom-color", bubbleBg),
          Declaration("border-top-color", "transparent"),
        ),
        Selector("[data-tooltip-position='bottom']:hover::after", Declaration("transform", "translate(-50%, 12px)")),
        Selector(
          "[data-tooltip-position='bottom']:focus-visible::after",
          Declaration("transform", "translate(-50%, 12px)"),
        ),

        // left: bubble to the left of the target, arrow points right.
        Selector(
          "[data-tooltip-position='left']::after",
          Declaration("left", "auto"),
          Declaration("right", "100%"),
          Declaration("bottom", "50%"),
          Declaration("transform", "translate(-4px, 50%)"),
        ),
        Selector(
          "[data-tooltip-position='left']::before",
          Declaration("left", "auto"),
          Declaration("right", "100%"),
          Declaration("bottom", "50%"),
          Declaration("transform", "translate(0, 50%)"),
          Declaration("border", "5px solid transparent"),
          Declaration("border-left-color", bubbleBg),
          Declaration("border-top-color", "transparent"),
        ),
        Selector("[data-tooltip-position='left']:hover::after", Declaration("transform", "translate(-12px, 50%)")),
        Selector(
          "[data-tooltip-position='left']:focus-visible::after",
          Declaration("transform", "translate(-12px, 50%)"),
        ),

        // right: bubble to the right of the target, arrow points left.
        Selector(
          "[data-tooltip-position='right']::after",
          Declaration("left", "100%"),
          Declaration("bottom", "50%"),
          Declaration("transform", "translate(4px, 50%)"),
        ),
        Selector(
          "[data-tooltip-position='right']::before",
          Declaration("left", "100%"),
          Declaration("bottom", "50%"),
          Declaration("transform", "translate(-100%, 50%)"),
          Declaration("border", "5px solid transparent"),
          Declaration("border-right-color", bubbleBg),
          Declaration("border-top-color", "transparent"),
        ),
        Selector("[data-tooltip-position='right']:hover::after", Declaration("transform", "translate(12px, 50%)")),
        Selector(
          "[data-tooltip-position='right']:focus-visible::after",
          Declaration("transform", "translate(12px, 50%)"),
        ),

        // Honor the OS reduce-motion setting: drop the slide transition; opacity-only fade.
        MediaQuery(
          Media.prefersReducedMotion.reduce,
          Selector("::after", Declaration("transition", "opacity 0.1s ease")),
          Selector(":hover::after", Declaration("transform", "translate(-50%, -8px)")),
          Selector(":focus-visible::after", Declaration("transform", "translate(-50%, -8px)")),
        ),
      )

end Tooltip
