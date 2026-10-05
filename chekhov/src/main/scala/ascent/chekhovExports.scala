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
}
// From the object itself: an export forwarder is not eligible for a second export.
export ascent.chekhov.Selectors.{testIdSelector, taggedSelector, taggedTestId, placeholderSelector, roleSelector}
