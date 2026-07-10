package ascent

/** Export facade for `ascent-js` (the DOM mount/binding engine + typed-event DSL).
  *
  * Contributes to the OPEN `package ascent` (see [[ascent.exports]] in dom-types). Re-exports the js public surface and
  * defines the `Ev` alias for the typed-event factory object.
  *
  * `TypedEvents` gives FULL typed-DOM events (`onClick(e => e.clientX)` where `e: dom.PointerEvent`). For the
  * lowest-common-denominator [[ascent.ast.AscentEvent]] (carrying `targetValue` / `key`, the two-way-binding
  * essentials), use the generated `Events` catalog via the `EventKey` DSL (`Events.onInput(e => e.targetValue)`),
  * re-exported from dom-types.
  */

// `AscentApp` is the browser mount entry point (`AscentApp.mount`/`mountBody`) — it supplies the JS `DomOps`
// capability + `DomStyleSink` to the cross-platform `Mount` engine (in ascent-mount-engine), so app code needn't
// name either. `ServerRegionRegistry`/`Diagnostics` are re-exported from mount-engine (same `ascent.js` package).
export ascent.js.{AscentApp, Canvas, TypedEvents, DomStyleSink, Lifecycle, Dom, ServerRegionRegistry, Diagnostics}

// The `EventTarget.addCssClass/removeCssClass` extensions (typed imperative class toggling from event handlers). They
// are top-level in `package ascent.js`, so re-export by name into the open `package ascent` for `import ascent.*`.
export ascent.js.{addCssClass, removeCssClass}

/** `Ev` — the typed-event factory, e.g. `Ev.onClick(e => ...)`. Same object as [[TypedEvents]]. */
type Ev = TypedEvents.type
val Ev: Ev = TypedEvents
