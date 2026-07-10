package ascent.css

/** Typed CSS property catalog.
  *
  * Pattern: each property is an `object foo extends DS("foo") with <ValueTraits>`. The value-grammar traits
  * ([[Styles.Length]], [[Styles.Percent]], [[Styles.ColorLike]], [[Styles.Auto]], [[Styles.None]], [[Styles.Normal]],
  * [[Styles.Numeric]]) mix in the typed builders that fit each property's CSS spec. Calling them returns a
  * [[Declaration]] carrying the property name + the rendered CSS value.
  *
  * **Generated catalog.** The bulk of the property objects are emitted by `ascent-domgen` from the vendored W3C
  * `webref` CSS data — see [[ascent.css.StylesGenerated]] (auto-generated, committed to the repo for review-ability).
  * The hand-written portion of this file holds:
  *
  *   - the foundation (`DS` class + value-grammar traits) every generated object extends;
  *   - the [[StylesOverrides]] trait, which carries hand-written `apply` overloads for properties whose typed
  *     shorthands the generator can't synthesize (e.g. `margin(t,r,b,l)`).
  *
  * Both layers compose: `object Styles extends StylesGenerated, StylesOverrides`.
  *
  * **Forward-compat:** the generator emits the *same* shape — `object foo extends DS("foo") with Length with Auto`.
  * Hand-written and generated objects are interchangeable; users see no call-site difference.
  */
object Styles extends StylesGenerated, StylesOverrides

/** Hand-written CSS property overrides — for properties whose shape exceeds the v1 generator's heuristics (typically:
  * shorthand `apply(...)` overloads). Mixed into [[Styles]] *after* [[StylesGenerated]], with the corresponding
  * properties skipped on the generator side (see `CssGenerator.overriddenProperties`) so there is no ambiguity.
  */
trait StylesOverrides:
  import StylesFoundation.*

  // 2- and 4-value shorthand variants for `margin` and `padding`. The generator emits the typed `Length`/`Percent`
  // mixins (single value); we add the multi-value `apply` overloads. TYPED ONLY — `margin(0, 14.px, 0, 20.px)`,
  // `margin(0, Length.auto)`; there is no `(String, …)` form (strings are error-prone). The single-value
  // `apply(String)` escape hatch on `DS` remains for un-modeled grammars.
  object margin extends DS("margin") with Length with Percent with Auto:
    def apply(vertical: ascent.css.Length, horizontal: ascent.css.Length): Declaration =
      apply(s"${vertical.render} ${horizontal.render}")
    def apply(
        top: ascent.css.Length,
        right: ascent.css.Length,
        bottom: ascent.css.Length,
        left: ascent.css.Length,
    ): Declaration =
      apply(s"${top.render} ${right.render} ${bottom.render} ${left.render}")
  end margin

  object padding extends DS("padding") with Length with Percent:
    def apply(vertical: ascent.css.Length, horizontal: ascent.css.Length): Declaration =
      apply(s"${vertical.render} ${horizontal.render}")
    def apply(
        top: ascent.css.Length,
        right: ascent.css.Length,
        bottom: ascent.css.Length,
        left: ascent.css.Length,
    ): Declaration =
      apply(s"${top.render} ${right.render} ${bottom.render} ${left.render}")
  end padding

  // CSS spec: `<font-weight-absolute> | bolder | lighter`, but font-weight also accepts
  // numeric weights (`100..900`) per the spec's <number> sub-grammar. The keyword set is
  // emitted by the generator; we add the integer overload here.
  object fontWeight extends DS("font-weight"):
    def bold: Declaration          = apply("bold")
    def normal: Declaration        = apply("normal")
    def bolder: Declaration        = apply("bolder")
    def lighter: Declaration       = apply("lighter")
    def apply(n: Int): Declaration = apply(n.toString)

  // `flex` shorthand: `<flex-grow> <flex-shrink>? <flex-basis>?`. The generated single-value mixins (Auto/None/Numeric)
  // can't express the multi-value form, so we own it here (typed, no String): `flex(0, 0, Length.auto)`,
  // `flex(1, 1, 0.px)`. The `flex-basis` arg is a Length (use `Length.auto` for the common `0 0 auto`).
  object flex extends DS("flex") with Auto with None with Numeric:
    def apply(grow: Double, shrink: Double, basis: ascent.css.Length): Declaration =
      apply(s"${formatDouble(grow)} ${formatDouble(shrink)} ${basis.render}")
end StylesOverrides

/** Foundation: the `DS` typed-property base class and the value-grammar traits every property object mixes in. Lives at
  * the package level so both the hand-written [[StylesOverrides]] and the generated [[StylesGenerated]] can extend `DS`
  * without routing through `Styles`.
  */
object StylesFoundation:

  /** A typed property constructor. The unparameterized `apply(String)` is always available as an escape hatch for raw
    * CSS literals; `apply(CssValue)` attaches any typed value (color, length, gradient, …) by rendering it at the leaf.
    *
    * Every property also inherits the five CSS-wide cascade keywords ([[inherit]] / [[initial]] / [[unset]] /
    * [[revert]] / [[revertLayer]]). They apply to ALL properties per the CSS Cascade spec but aren't in the webref
    * per-property data, so they live here once rather than being generated onto each object.
    */
  abstract class DS(val property: String):
    def apply(value: String): Declaration   = Declaration(property, value)
    def apply(value: CssValue): Declaration = Declaration(property, value.render)

    /** `inherit` — take the computed value of this property from the parent element. */
    def inherit: Declaration = apply("inherit")

    /** `initial` — reset this property to its spec-defined initial value. */
    def initial: Declaration = apply("initial")

    /** `unset` — `inherit` if the property is inherited, else `initial`. */
    def unset: Declaration = apply("unset")

    /** `revert` — roll back to the value the previous cascade origin (user-agent / user stylesheet) would give. */
    def revert: Declaration = apply("revert")

    /** `revert-layer` — roll back to the value from the previous cascade LAYER. */
    def revertLayer: Declaration = apply("revert-layer")
  end DS

  /** Public base for a USER-DEFINED typed CSS property — the same `DS` the generated catalog extends, exposed so the
    * ADT system is open, not closed to webref. Define a property webref doesn't ship (a vendor prefix, a bleeding-edge
    * or custom property) exactly as the generator would, mixing in any value-grammar traits:
    * {{{
    *   import ascent.*
    *   object webkitBackdropFilter extends Prop("-webkit-backdrop-filter") with Filterish
    *   // now: webkitBackdropFilter.blur(Length.px(24))  /  webkitBackdropFilter(Filter.list(...))
    * }}}
    * Inherits `apply(String)` / `apply(CssValue)` and the cascade keywords from [[DS]]; mix [[Length]], [[ColorLike]],
    * [[Filterish]], … for typed builders.
    */
  abstract class Prop(property: String) extends DS(property)

  /** Render a Double in a platform-stable form: integer-valued doubles get a trailing `.0` (so `24.0` round-trips as
    * `"24.0"` on both JVM and Scala.js). Without this, Scala.js's `Double.toString` drops the trailing `.0` for whole
    * numbers, diverging from JVM and making CSS output platform-dependent.
    */
  private[css] def formatDouble(n: Double): String =
    val s = n.toString
    if s.contains('.') || s.contains('e') || s.contains('E') || !n.isFinite then s
    else s + ".0"

  /** Mixin: `<property>: <n><unit>;` — every CSS length unit. */
  trait Length extends DS:
    private def suffixed(s: String)(n: Double): Declaration = apply(s"${formatDouble(n)}$s")
    val px: Double => Declaration                           = suffixed("px")
    val em: Double => Declaration                           = suffixed("em")
    val rem: Double => Declaration                          = suffixed("rem")
    val pt: Double => Declaration                           = suffixed("pt")
    val pc: Double => Declaration                           = suffixed("pc")
    val cm: Double => Declaration                           = suffixed("cm")
    val mm: Double => Declaration                           = suffixed("mm")
    val in: Double => Declaration                           = suffixed("in")
    val q: Double => Declaration                            = suffixed("Q")
    val ex: Double => Declaration                           = suffixed("ex")
    val ch: Double => Declaration                           = suffixed("ch")
    val vh: Double => Declaration                           = suffixed("vh")
    val vw: Double => Declaration                           = suffixed("vw")
    val vmin: Double => Declaration                         = suffixed("vmin")
    val vmax: Double => Declaration                         = suffixed("vmax")

    /** Unitless `0` — the only length value that's allowed without a unit suffix. */
    val zero: Declaration = apply("0")
  end Length

  /** Mixin: `<property>: <n>%;` */
  trait Percent extends DS:
    def pct(n: Double): Declaration = apply(s"${formatDouble(n)}%")

  /** Length OR percentage — the most common combination. */
  trait LengthPercent extends Length, Percent

  /** Mixin: `<property>: auto;` */
  trait Auto extends DS:
    def auto: Declaration = apply("auto")

  /** Mixin: `<property>: none;` */
  trait None extends DS:
    def none: Declaration = apply("none")

  /** Mixin: `<property>: normal;` */
  trait Normal extends DS:
    def normal: Declaration = apply("normal")

  /** Mixin: numeric value (Int or Double rendered as-is). */
  trait Numeric extends DS:
    def apply(n: Int): Declaration    = apply(n.toString)
    def apply(n: Double): Declaration = apply(formatDouble(n))

  /** Mixin: color values with named convenience constructors. Builds typed [[Color]] values and attaches them via the
    * universal `apply(CssValue)` overload, so the rendered output is the CSS Color 4 form (`rgb(r, g, b)` /
    * `rgb(r g b / a)`). For richer authoring — hex tokens, alpha/lighten derivation — reach for [[Color]] directly.
    */
  trait ColorLike extends DS:
    def rgb(r: Int, g: Int, b: Int): Declaration             = apply(Color.rgb(r, g, b))
    def rgba(r: Int, g: Int, b: Int, a: Double): Declaration = apply(Color.rgba(r, g, b, a))

  // value-ADT mixins: wire a property whose CSS grammar references a typed value (`<transform-function>`, `<filter-function>`,
  // `<shadow>`, `<image>`, `<angle>`, `<time>`, `<single-transition>`) to its hand-written value ADT, so the typed
  // builders are DISCOVERABLE by autocomplete ON THE PROPERTY (`transform.translateY(...)`) rather than only via the
  // universal `apply(CssValue)`. The generator mixes these in from `PropertyAnalyzer.typeRefTraits`. The escape hatch
  // (`apply(CssValue)` / `apply(String)`) stays for compositions the shortcuts don't cover.

  // NOTE: each varargs `apply(X*)` widens its built value to `CssValue` before calling `apply`, so it binds the
  // universal `DS.apply(CssValue)` overload. Without the ascription, `apply(aFilter)` would re-bind to `apply(Filter*)`
  // (more specific for a single arg) and recurse forever.

  /** Mixin: a `transform` list. Single-function shortcuts plus `apply(Transform*)` for the space-joined multi form. */
  trait Transformish extends DS:
    def apply(fns: ascent.css.Transform*): Declaration = apply(ascent.css.Transform.list(fns*): CssValue)
    def translate(x: ascent.css.Length, y: ascent.css.Length): Declaration =
      apply(ascent.css.Transform.translate(x, y): CssValue)
    def translateX(x: ascent.css.Length): Declaration = apply(ascent.css.Transform.translateX(x): CssValue)
    def translateY(y: ascent.css.Length): Declaration = apply(ascent.css.Transform.translateY(y): CssValue)
    def scale(n: Double): Declaration                 = apply(ascent.css.Transform.scale(n): CssValue)
    def rotate(a: ascent.css.Angle): Declaration      = apply(ascent.css.Transform.rotate(a): CssValue)

  /** Mixin: a `filter` / `backdrop-filter` list. */
  trait Filterish extends DS:
    def apply(fns: ascent.css.Filter*): Declaration  = apply(ascent.css.Filter.list(fns*): CssValue)
    def blur(radius: ascent.css.Length): Declaration = apply(ascent.css.Filter.blur(radius): CssValue)
    def brightness(n: Double): Declaration           = apply(ascent.css.Filter.brightness(n): CssValue)
    def saturate(n: Double): Declaration             = apply(ascent.css.Filter.saturate(n): CssValue)

  /** Mixin: a `box-shadow` / `text-shadow`. `apply(Shadow*)` comma-joins multiple layers. */
  trait Shadowish extends DS:
    def apply(shadows: ascent.css.Shadow*): Declaration = apply(ascent.css.Shadow.list(shadows*): CssValue)

  /** Mixin: an image value (`background-image`, `mask-image`, …). Typed gradients and `url(...)` attach here. */
  trait Imageish extends DS:
    def apply(gradient: ascent.css.Gradient): Declaration = apply(gradient: CssValue)
    def apply(image: ascent.css.Image): Declaration       = apply(image: CssValue)
    def url(href: String): Declaration                    = apply(ascent.css.Image.url(href): CssValue)

  /** Mixin: an `<easing-function>` — the curve for `transition`/`animation` timing properties. */
  trait TimingFunctionish extends DS:
    def apply(fn: ascent.css.TimingFunction): Declaration = apply(fn: CssValue)
    def ease: Declaration                                 = apply(ascent.css.TimingFunction.ease: CssValue)
    def linear: Declaration                               = apply(ascent.css.TimingFunction.linear: CssValue)
    def easeIn: Declaration                               = apply(ascent.css.TimingFunction.easeIn: CssValue)
    def easeOut: Declaration                              = apply(ascent.css.TimingFunction.easeOut: CssValue)
    def easeInOut: Declaration                            = apply(ascent.css.TimingFunction.easeInOut: CssValue)
    def stepStart: Declaration                            = apply(ascent.css.TimingFunction.stepStart: CssValue)
    def stepEnd: Declaration                              = apply(ascent.css.TimingFunction.stepEnd: CssValue)
    def cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double): Declaration =
      apply(ascent.css.TimingFunction.cubicBezier(x1, y1, x2, y2): CssValue)
    def steps(n: Int): Declaration               = apply(ascent.css.TimingFunction.steps(n): CssValue)
    def steps(n: Int, jump: String): Declaration = apply(ascent.css.TimingFunction.steps(n, jump): CssValue)
  end TimingFunctionish

  /** Mixin: a `font-family` stack. Build the stack with [[ascent.css.FontFamily]] (`FontFamily.of("Inter",
    * FontFamily.sansSerif)`) and attach via `apply(FontFamily)` or the `of(...)` shortcut. (No `apply(String*)`
    * convenience — it would collide with the universal `apply(String)` escape hatch on a single argument.)
    */
  trait FontFamilyish extends DS:
    def apply(family: ascent.css.FontFamily): Declaration = apply(family: CssValue)
    def of(families: ascent.css.FontFamily*): Declaration = apply(ascent.css.FontFamily.of(families*): CssValue)

  /** Mixin: a `<position>`-valued property (`background-position`, `object-position`, …). Keyword corners + `at(x, y)`.
    */
  trait Positionish extends DS:
    def apply(pos: ascent.css.Position): Declaration                = apply(pos: CssValue)
    def at(x: ascent.css.Length, y: ascent.css.Length): Declaration = apply(ascent.css.Position.at(x, y): CssValue)
    def center: Declaration                                         = apply(ascent.css.Position.center: CssValue)
    def topLeft: Declaration                                        = apply(ascent.css.Position.topLeft: CssValue)
    def topRight: Declaration                                       = apply(ascent.css.Position.topRight: CssValue)
    def bottomLeft: Declaration                                     = apply(ascent.css.Position.bottomLeft: CssValue)
    def bottomRight: Declaration                                    = apply(ascent.css.Position.bottomRight: CssValue)

  /** Mixin: a `<basic-shape>`-valued property (`clip-path`, `shape-outside`) — `circle`/`ellipse`/`inset`/`polygon`/
    * `path` via [[ascent.css.BasicShape]].
    */
  trait BasicShapeish extends DS:
    def apply(shape: ascent.css.BasicShape): Declaration = apply(shape: CssValue)

  /** Mixin: a grid track / track-list property (`grid-template-columns`, `grid-auto-rows`, …). Build with
    * [[ascent.css.GridTrack]] (`GridTrack.list(GridTrack.fr(1), GridTrack.repeat(2, GridTrack.auto))`).
    */
  trait GridTrackish extends DS:
    def apply(track: ascent.css.GridTrack): Declaration  = apply(track: CssValue)
    def list(tracks: ascent.css.GridTrack*): Declaration = apply(ascent.css.GridTrack.list(tracks*): CssValue)

  /** Mixin: the legacy `clip` property's `rect(<top>, <right>, <bottom>, <left>)` value (the only property using this
    * shape). Build with [[ascent.css.Clip]]: `clip(Clip.rect(0.px, 0.px, 0.px, 0.px))` or the `rect(...)` shortcut.
    */
  trait Clipish extends DS:
    def apply(c: ascent.css.Clip): Declaration = apply(c: CssValue)
    def rect(
        top: ascent.css.Length,
        right: ascent.css.Length,
        bottom: ascent.css.Length,
        left: ascent.css.Length,
    ): Declaration =
      apply(ascent.css.Clip.rect(top, right, bottom, left): CssValue)

  /** Mixin: a `<line-width> || <line-style> || <color>` shorthand — `border`/`border-top`/…/`outline`/`column-rule`.
    * Build with [[ascent.css.Border]]: `border(Border(1.px, LineStyle.Solid, color))` or `border.solid(1.px, color)`.
    */
  trait Borderish extends DS:
    def apply(b: ascent.css.Border): Declaration                              = apply(b: CssValue)
    def solid(width: ascent.css.Length, color: ascent.css.Color): Declaration =
      apply(ascent.css.Border.solid(width, color): CssValue)
    def dashed(width: ascent.css.Length, color: ascent.css.Color): Declaration =
      apply(ascent.css.Border.dashed(width, color): CssValue)

  /** Mixin: a 1–4 value `<length-percentage>` box shorthand — `inset`, `border-width`, `border-radius`,
    * `scroll-margin`, the logical `margin-block`/`inline`, etc. (The single-value form comes from the
    * `Length`/`Percent` mixins / numeric extensions; this adds the multi-value typed overloads.) Typed only —
    * `inset(0, 14.px)`, `borderRadius(4.px, 8.px, 4.px, 8.px)`; `Length.auto` covers keyword sides.
    */
  trait LengthBox extends DS:
    def apply(a: ascent.css.Length, b: ascent.css.Length): Declaration =
      apply(s"${a.render} ${b.render}")
    def apply(a: ascent.css.Length, b: ascent.css.Length, c: ascent.css.Length): Declaration =
      apply(s"${a.render} ${b.render} ${c.render}")
    def apply(a: ascent.css.Length, b: ascent.css.Length, c: ascent.css.Length, d: ascent.css.Length): Declaration =
      apply(s"${a.render} ${b.render} ${c.render} ${d.render}")

  /** Mixin: an `<angle>`-valued property (e.g. `rotate`). */
  trait Angleish extends DS:
    def deg(n: Double): Declaration  = apply(ascent.css.Angle.deg(n): CssValue)
    def turn(n: Double): Declaration = apply(ascent.css.Angle.turn(n): CssValue)
    def rad(n: Double): Declaration  = apply(ascent.css.Angle.rad(n): CssValue)
    def grad(n: Double): Declaration = apply(ascent.css.Angle.grad(n): CssValue)

  /** Mixin: a `<time>`-valued property (transition/animation duration + delay). */
  trait Timeish extends DS:
    def s(n: Double): Declaration  = apply(ascent.css.Time.s(n): CssValue)
    def ms(n: Double): Declaration = apply(ascent.css.Time.ms(n): CssValue)

  /** Mixin: a `transition` shorthand list. `apply(Transition*)` comma-joins multiple property transitions. */
  trait Transitionish extends DS:
    def apply(transitions: ascent.css.Transition*): Declaration =
      apply(ascent.css.Transition.list(transitions*): CssValue)

end StylesFoundation
