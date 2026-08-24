package ascent

/** Export facade for `ascent-chekhov`. Contributes the shared handle types to the open `package ascent` so tests can
  * `import ascent.*` and see [[InputHandle]] / [[ButtonHandle]]. Platform backends (`withMounted`, `Page` extensions)
  * are exported from the js / jvm trees.
  */
export ascent.chekhov.{
  HandleBackend,
  ElementHandle,
  InputHandle,
  TextAreaHandle,
  ButtonHandle,
  SelectHandle,
  HtmlTag,
  TagHandle,
  Selectors,
  testIdSelector,
  taggedSelector,
  taggedTestId,
  placeholderSelector,
  roleSelector,
}
