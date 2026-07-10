package ascent.css

/** A CSS container name — the `<custom-ident>` shared by the `container-name` property and an `@container <name>`
  * query.
  *
  * Modeled as a value (not a bare string) so a name is declared ONCE and referenced from both sites — the
  * `container-name` declaration and the `@container` query that targets it — eliminating the drift risk of repeating
  * the literal:
  * {{{
  *   val Card = ContainerName("todo-card")
  *   …
  *   containerName(Card)                              // container-name: todo-card;
  *   ContainerQuery.named(Card, Container.maxWidth.px(420), …)   // @container todo-card (max-width: 420px) { … }
  * }}}
  *
  * Attaches to the `container-name` property via the universal `apply(CssValue)` overload; the `none` keyword stays on
  * the generated property. (`container-name` also accepts multiple idents; this models the common single-name case.)
  */
final case class ContainerName(ident: String) extends CssValue:
  def render: String = ident
