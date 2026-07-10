package ascent.css

/** Typed `@container` query catalog.
  *
  * `Container.<feature>` is the entry point for every spec-listed container query feature; values produce a
  * [[ContainerCondition]] that flows directly into [[ContainerQuery]]:
  *
  * {{{
  *   import ascent.css.{Container, ContainerQuery}
  *
  *   ContainerQuery(Container.minWidth.px(400), ...members)
  *   ContainerQuery(Container.inlineSize.px(600) and Container.orientation.portrait, ...members)
  *   ContainerQuery.named("my-card", Container.minWidth.px(400), ...members)
  * }}}
  *
  * **Generated catalog.** All the per-feature objects come from `ascent-domgen`, which reads the vendored W3C `webref`
  * CSS data — see [[ascent.css.ContainerFeatures]] (auto-generated, committed). The hand-written portion is just the
  * entry point + the foundation traits in [[ContainerFoundation]] every generated feature object extends.
  */
object Container extends ContainerFeatures
