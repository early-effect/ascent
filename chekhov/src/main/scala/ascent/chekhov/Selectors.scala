package ascent.chekhov

import ascent.domtypes.{ElementKey, VoidElementKey}

/** CSS selector fragments built from the HTML lattice. No live DOM, no Playwright. */
object Selectors:

  /** Escape a value that will sit inside a double-quoted CSS attribute selector. */
  def escapeAttr(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

  def testIdSelector(testId: String): String =
    s"""[data-testid="${escapeAttr(testId)}"]"""

  def taggedSelector(tagName: String, testId: String): String =
    s"${tagName}${testIdSelector(testId)}"

  def taggedTestId(testId: String, tag: ElementKey | VoidElementKey): String =
    val name = tag match
      case k: ElementKey     => k.domName
      case k: VoidElementKey => k.domName
    taggedSelector(name, testId)

  def placeholderSelector(tagName: String, text: String): String =
    s"""${tagName}[placeholder="${escapeAttr(text)}"]"""

  def roleSelector(role: String): String =
    s"""[role="${escapeAttr(role)}"]"""
end Selectors

export Selectors.{escapeAttr, testIdSelector, taggedSelector, taggedTestId, placeholderSelector, roleSelector}
