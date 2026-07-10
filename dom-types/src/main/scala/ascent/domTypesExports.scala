package ascent

/** Export facade for `ascent-dom-types`.
  *
  * `package ascent` is an OPEN package shared across every ascent jar, so a single `import ascent.*` in user code
  * unions the export lists each module contributes — no need to reach into `ascent.domtypes.*` (or `ascent.ast.*`,
  * `ascent.css.*`, …) by hand. This file re-exports the dom-types public surface and defines the terse aliases that
  * mirror preactile's `E` / `A` / `S` (here `E` = Elements, `A` = Attrs, `Aria` = AriaAttrs).
  *
  * Stability: every catalog is a single, stable top-level object (`Elements`, `Attrs`, `AriaAttrs`, `Events`) whose
  * MEMBERS are generated but whose NAME never changes. View code references the object, so these re-exports don't drift
  * when the generator adds an element or attribute — the generator is untouched.
  */

export ascent.domtypes.{Elements, Attrs, AriaAttrs, Events, AttrValue, AttrKey, ElementKey, EventKey, Codec}

/** `E` — the HTML element catalog, e.g. `E.div`, `E.input`. Same object as [[Elements]]. */
type E = Elements.type
val E: E = Elements

/** `A` — the HTML attribute catalog, e.g. `A.className`, `A.`type``. Same object as [[Attrs]]. */
type A = Attrs.type
val A: A = Attrs

/** `Aria` — the ARIA attribute catalog, e.g. `Aria.role`, `Aria.ariaLabel`. Same as [[AriaAttrs]]. */
type Aria = AriaAttrs.type
val Aria: Aria = AriaAttrs
