# Design: generate keyword `CssValue` enums (shorthand parts)

## Problem

Keyword `<type>`s (e.g. `<single-animation-fill-mode> = none | forwards | backwards | both`) are generated
ONLY as property-bound `Declaration` traits in `StylesValueTraits.scala` — `animationFillMode.both` renders
`animation-fill-mode: both;`. There is no composable *value* form that renders the bare token `both`, so a
typed shorthand builder (`animation: <name> <dur> <timing> <count> <fill>`) can't reuse them. Today the only
composable `CssValue`s are hand-authored (`Time`, `TimingFunction`, `Transition`), bridged to properties by a
hand-curated allowlist (`PropertyAnalyzer.typeRefTraits` → `*ish` traits).

## Which types get a value enum? (data-driven, not a hand-list)

A keyword `<type>` needs a composable value form **iff it appears as a `TypeRef` leaf of some shorthand's
`||` (AnyOrderOneOrMore) or `&&` (AllInAnyOrder) grammar** — i.e. it's a *component of a shorthand value*, not
a standalone single-keyword property value. `<single-animation>`'s `||` grammar names exactly
`<single-animation-iteration-count|direction|fill-mode|play-state>` (+ `<easing-function>`/`<time>`, already
hand-authored ADTs). This selector is computed by scanning all value-defs' grammars for shorthand-component
TypeRefs — no curated list. Call this set `shorthandComponentTypes`.

Scope check (measured via `domgen/runMain ascent.domgen.css.CssInspect shorthand-parts`): 142 keyword traits
total; **13** are shorthand components. Standalone keyword properties (`display`, `cursor`) keep ONLY their
property-bound trait — no value enum, nothing composes them.

The 13 (bare type → keywords → referencing shorthand):
- `<single-animation-direction>` reverse|alternate|alternate-reverse — `<single-animation>`
- `<single-animation-fill-mode>` forwards|backwards|both — `<single-animation>`
- `<single-animation-iteration-count>` infinite (+ `<number>`) — `<single-animation>`
- `<single-animation-play-state>` running|paused — `<single-animation>`
- `<single-transition-property>` all — `<single-transition>`
- `<line-style>` hidden|dotted|dashed|solid|double|groove|ridge|inset|outset — border shorthands
- `<line-width>` thin|medium|thick|hairline (+ `<length>`) — border, -webkit-text-stroke
- `<attachment>` scroll|fixed|local — background/`<bg-layer>`
- `<repeat-style>` repeat-x|repeat-y|repeat|space|round|no-repeat — background/mask
- `<visual-box>` content-box|padding-box|border-box — background, overflow-clip-margin*
- `<geometry-box>` fill-box|stroke-box|view-box — `<mask-layer>`
- `<compositing-operator>` add|subtract|intersect|exclude — `<mask-layer>`
- `<masking-mode>` alpha|luminance|match-source — `<mask-layer>`

Note `<line-style>`/`<line-width>` already back the hand-authored `Border`/`Borderish` shorthand; generating
their value enums makes them consistent but they're already composable via `Border`. The animation set (4) is
the immediate driver.

## What gets generated — and the KEY simplification

**Verified precedent (`LineStyle`):** the generated keyword traits in `StylesValueTraits.scala` are NESTED
inner traits of `trait StylesValueTraits`. A top-level hand-authored `enum LineStyle extends CssValue`
(Border.scala) ALREADY coexists with the nested `trait LineStyle` — `object border … with LineStyle`
compiles, `Border(1.px, LineStyle.Solid, …)` uses the enum, `LineStyle` is exported. Different scope + kind →
no collision, **no rename needed.**

So the design is minimal: **generate a top-level `enum X extends CssValue` for each shorthand-component type;
leave the nested keyword trait exactly as-is.** No `*ish` rename, no `StylesGenerated` property re-emit, no
MaskLayer/ShapeBox composition ripple. The nested trait keeps giving `animationFillMode.both` (property-bound
keyword); the new top-level enum gives the composable value `SingleAnimationFillMode.Both`.

For each `bare` in `shorthandComponentTypes`:
```scala
/** `<single-animation-fill-mode>` — `none | forwards | backwards | both` */
enum SingleAnimationFillMode(val render: String) extends CssValue:
  case None      extends SingleAnimationFillMode("none")
  case Forwards  extends SingleAnimationFillMode("forwards")
  case Backwards extends SingleAnimationFillMode("backwards")
  case Both      extends SingleAnimationFillMode("both")
  override def toString = render
```
Emitted into a NEW generated file `StylesKeywordValues.scala` (top-level enums in `package ascent.css`).
`<number>`-bearing components (`<single-animation-iteration-count> = infinite | <number>`) get a numeric
case too: `case Count(n: Double) extends SingleAnimationIterationCount(formatDouble(n))`.

## Collisions (from the full 13-type map)

- **`line-style`** → SKIP enum generation: `enum LineStyle` already hand-authored in Border.scala (exported,
  used by `Border`). Keep it. (Generate the other 12.)
- **`line-width`** → generate `enum LineWidth { Thin/Medium/Thick/Hairline }` — additive, no existing symbol;
  gives `borderWidth(LineWidth.Thin)`. (The `<length>` branch still flows via the property's `Length` mixin.)
- The other 11 are clean — no top-level name exists, no hand-authored value type.
- **`reservedTraitNames`**: do NOT add the enum names. That set forces a `Kw` suffix on the GENERATED NESTED
  trait of the same name — but here the enum and the nested trait DELIBERATELY share a name (top-level enum vs
  member trait, different scope, no clash — the `LineStyle` precedent). Adding them would rename the very
  traits we keep and break the property mixins. The enum/trait name-sharing is the intended design.

## Numeric-case detection

A component type whose grammar has a `<number>`/`<integer>`/`<time>`/`<length>`/`<percentage>` TypeRef branch
alongside its keywords (e.g. iteration-count `infinite | <number>`, line-width `<length> | thin|…`) gets an
extra parameterized case wrapping the dimension. Keep it narrow: only `<number>`/`<integer>` → `Count(n:
Double)`; the `<length>`/`<time>` branches are already reachable via the property's own `Length`/`Timeish`
mixin, so the enum need only add the KEYWORD cases + (for counts) the number case. Simplest correct rule:
enum carries the keyword cases always, plus a `Count(Double)` case iff the grammar surfaces `<number>`.

## Shorthand builder

`animation` shorthand composes the parts like `Transition` composes `Time`+`TimingFunction`. Hand-authored
`Animation` value type OR generated — TBD (see Transition question). `Keyframes.use` becomes:
```scala
def use(duration: Time, timing: TimingFunction = TimingFunction.ease, delay: Option[Time] = None,
        iterations: SingleAnimationIterationCount = SingleAnimationIterationCount.once,
        direction: SingleAnimationDirection = SingleAnimationDirection.normal,
        fill: SingleAnimationFillMode = SingleAnimationFillMode.None): Declaration
def use(rest: String): Declaration  // escape hatch retained
```

## Transition assessment (user asked to re-examine) — CONCLUSION: already consistent, no refactor

`Transition` is hand-authored and predates this work. Checked against the new model:

- Its composable parts are ALREADY typed values: `Time` (duration/delay) + `TimingFunction` (easing). Neither
  is a keyword enum — they're the same hand-authored `CssValue` ADTs the animation shorthand reuses. ✓
- Its one open-ended part is the animated PROPERTY NAME (`property: String`). CSS `transition-property` is
  `none | all | <custom-ident>#` — a property name is genuinely open (any CSS property), NOT a fixed keyword
  set. `Transition` already handles this the right way: a typed `apply(property: DS, …)` overload reads the
  name from a property-catalog object (`Transition(Styles.color, Time.s(0.2))`), with a `String` overload as
  the escape hatch. The generated `enum SingleTransitionProperty` only has the `all` keyword (correctly — it's
  the only keyword in the grammar); it is NOT a substitute for a property-name reference.

So `Transition` is already the same shape the animation shorthand now follows (compose typed value parts;
typed-catalog reference for the open-ended part; String escape hatch). No refactor needed. The only future
convergence would be generating shorthand STRUCTS wholesale (an `Animation`/`Transition` value type from the
`<single-*>` grammar) — deliberately out of scope; the parts-as-values layer is the reusable win, and
`Keyframes.use` composes them inline without a dedicated struct.

## Census / tests

- `ValueTraitCensusSpec` gains: every `shorthandComponentType` produces BOTH a value enum and a `*ish` trait;
  no enum name collides with a hand-authored `CssValue`; representative enum (`SingleAnimationFillMode`) has
  its 4 cases + renders bare tokens.
- New `StylesKeywordValuesSpec` (css): each generated enum's `.render` is the bare token; attaches via
  `DS.apply(CssValue)`.

## Files

- domgen: NEW `KeywordValueCatalog.scala` (compute `shorthandComponentTypes` + build enums), renderer method
  in `CssRenderer`, wire in `Main` (write `StylesKeywordValues.scala`), adjust `PropertyAnalyzer.typeRefTraits`
  + `ValueTraitCatalog` naming/exclusion, extend `reservedTraitNames`.
- css generated: NEW `StylesKeywordValues.scala`; `StylesValueTraits.scala` + `StylesGenerated.scala` re-emit
  (traits renamed `*ish`).
- css hand: `Keyframes.use` typed overload; maybe `Animation` value.
- exports: `cssExports.scala` re-export the new enums.
- example: migrate `.use(...)` sites to typed args.
