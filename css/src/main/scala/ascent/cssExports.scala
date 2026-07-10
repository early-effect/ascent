package ascent

/** Export facade for `ascent-css`.
  *
  * Contributes to the OPEN `package ascent` (see [[ascent.exports]] in dom-types). Re-exports the CSS *authoring*
  * surface so users build stylesheets without `import ascent.css.*`.
  *
  * Note the division of labour:
  *   - the CSS *property catalog* (`color`, `padding`, `display`, …) is reached via the `S` alias (`S.color("red")`) or
  *     a deliberate `import ascent.css.Styles.*` wildcard when you want bare property names;
  *   - the *authoring types* below (`CssClass`, `Selector`, `Declaration`, at-rule queries, the
  *     `@font-face`/`@page`/`@counter-style`/`@keyframes` builders, `Tooltip`) come through the facade so they're
  *     available under a plain `import ascent.*`.
  *
  * Internal foundation/mixin traits (`StylesFoundation`, `MediaFeatures`, `ContainerFeatures`, …) are intentionally NOT
  * re-exported — users reference the catalog objects, never the mixins.
  */

// CSS class authoring + per-node scoping + page-level chrome.
export ascent.css.{CssClass, CssScope, GlobalStyle, GlobalRule, StyleSink, StateAttr}

// Typed selector surface: the `Sel` fragment ADT + its constructors, pseudo catalogs, attribute operators, the `An+B`
// helper, and the `Cls`/`Id`/`Elem` selector-fragment constructors. (`Selector` itself is exported below.) `Elem` is
// the element-selector catalog (`Elem.button`) — named `Elem`, not `Tag`, to avoid clashing with `zio.Tag`.
export ascent.css.{Sel, PseudoClass, PseudoElement, Nth, AttrOp, Cls, Id, Elem}

// Rule-body primitives and at-rule queries.
export ascent.css.{
  Declaration,
  Selector,
  CssMember,
  CssValue,
  Color,
  Length,
  Angle,
  Time,
  Gradient,
  RadialGeometry,
  RadialShape,
  RadialExtent,
  ColorStop,
  Shadow,
  Transform,
  Filter,
  Transition,
  TimingFunction,
  FontFamily,
  Image,
  Position,
  BasicShape,
  GridTrack,
  Border,
  LineStyle,
  Clip,
  ContainerName,
  AtRuleBlock,
  MediaQuery,
  ContainerQuery,
  SupportsQuery,
  MediaCondition,
  ContainerCondition,
  SupportsCondition,
}

// Catalog objects + at-rule builders.
export ascent.css.{Styles, Media, Container, Supports, FontFace, Page, CounterStyle, Keyframes, Frame}

// At-rule descriptor catalogs (the `@font-face` / `@page` / `@counter-style` property names).
// Stable generated objects, like `Styles` — referenced by name, so re-exporting them never drifts.
export ascent.css.{FontFaceDescriptors, PageDescriptors, CounterStyleDescriptors}

// Generated keyword VALUE enums — the composable `CssValue` form of shorthand-component keyword `<type>`s (spliced
// into typed shorthands like `Animation`). Their nested property-bound trait namesakes live inside StylesValueTraits
// (path-dependent, not exported), so these top-level enum names are unambiguous under `import ascent.*`.
export ascent.css.{
  Attachment,
  CompositingOperator,
  GeometryBox,
  LineWidth,
  MaskingMode,
  RepeatStyle,
  SingleAnimationDirection,
  SingleAnimationFillMode,
  SingleAnimationIterationCount,
  SingleAnimationPlayState,
  SingleTransitionProperty,
  VisualBox,
}

// CSS utility primitives.
export ascent.css.Tooltip

// Numeric → Length ergonomic syntax: `14.px`, `1.5.rem`, `50.pct`. The extensions are top-level in `package
// ascent.css` (so `import ascent.css.*` gets them); re-export here so a plain `import ascent.*` works too.
export ascent.css.{px, em, rem, pt, pc, cm, mm, q, ex, ch, vh, vw, vmin, vmax, pct}

/** User-extension surface: define a typed CSS property webref doesn't ship — `object myProp extends Prop("my-prop")
  * with Filterish` — exactly as the generated catalog does. `Prop` is the public base; the value-ADT mixins below are
  * the same traits the generator wires onto properties.
  *
  * The value-GRAMMAR mixins (`Length`/`Percent`/`Numeric`/`ColorLike`) are intentionally NOT re-exported here — their
  * names collide with the value-TYPE ADTs (`Length`, `Color`, …) already in `ascent.*`. A custom prop that needs them
  * imports them directly: `import ascent.css.StylesFoundation.Length`.
  */
export ascent.css.StylesFoundation.{
  Prop,
  Transformish,
  Filterish,
  Shadowish,
  Imageish,
  Angleish,
  Timeish,
  Transitionish,
  TimingFunctionish,
  FontFamilyish,
  Positionish,
  BasicShapeish,
  GridTrackish,
  Borderish,
  LengthBox,
  Clipish,
}

/** `S` — the CSS property catalog, e.g. `S.color("red")`, `S.padding.px(8)`. Same object as [[Styles]]. For bare
  * property names (`color(...)`), import the catalog directly: `import ascent.css.Styles.*`.
  */
type S = Styles.type
val S: S = Styles
