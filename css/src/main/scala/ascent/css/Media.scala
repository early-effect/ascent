package ascent.css

/** Typed `@media` query catalog.
  *
  * `Media.<feature>` is the entry point for every spec-listed media feature; values produce a [[MediaCondition]] that
  * flows directly into [[MediaQuery]]:
  *
  * {{{
  *   import ascent.css.{Media, MediaQuery}
  *
  *   MediaQuery(Media.prefersReducedMotion.reduce, ...members)
  *   MediaQuery(Media.maxWidth.px(600), ...members)
  *   MediaQuery(Media.maxWidth.px(600) and Media.hover.hover, ...members)
  *   MediaQuery(Media.orientation.portrait or Media.maxWidth.px(800), ...members)
  *   MediaQuery(Media.hover.none.not, ...members)
  * }}}
  *
  * **Generated catalog.** All the per-feature objects come from `ascent-domgen`, which reads the vendored W3C `webref`
  * CSS data — see [[ascent.css.MediaFeatures]] (auto-generated, committed to the repo for review-ability). The
  * hand-written portion is just the entry point + the foundation traits in [[MediaFoundation]] every generated feature
  * object extends.
  */
object Media extends MediaFeatures
