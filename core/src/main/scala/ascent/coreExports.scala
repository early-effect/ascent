package ascent

/** Export facade for `ascent-core` (the AST + Squawk reactive primitive).
  *
  * Contributes to the OPEN `package ascent`, so `import ascent.*` brings these names in alongside whatever other ascent
  * modules are on the classpath (dom-types, css, js, conduit). See [[ascent.exports]] in dom-types for the open-package
  * rationale.
  *
  * NOT re-exported here: the `ascent.dsl` package (the `ElementKey`/`AttrKey`/`EventKey` `apply` extensions,
  * `when`/`forEach`/`text`/`scoped`, and the `Arg` `given Conversion`s). Extension and implicit resolution require a
  * DIRECT wildcard of the defining scope, so view code keeps a second `import ascent.dsl.*` line — the accepted floor.
  */

// AST — what the DSL builds and the mount/SSR backends interpret.
export ascent.ast.{AscentEvent, Attr, UI}

// Squawk — the reactive value-over-time. `sq` is the top-level constructor; `Eq` the dedup
// typeclass; `Source`/`Subscription` the concrete handle types users occasionally name.
export ascent.squawk.{Squawk, Source, Subscription, Eq, sq}
