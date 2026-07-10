package ascent.domtypes

/** The HTML void elements — those that take no children and have no closing tag (`<br>`, `<input>`, …).
  *
  * The typed element catalog distinguishes these at compile time via [[VoidElementKey]] (so the DSL rejects children),
  * but the [[ascent.ast.UI.Element]] AST stores only a `tag: String`. A server-side HTML renderer therefore needs the
  * tag-name set to decide whether to self-close. webref doesn't flag void-ness, so — like the generator's own
  * `DefBuilder.voidElements` — we keep the canonical HTML-spec list here, in the zero-dependency, platform-neutral
  * module that already owns element metadata.
  */
object VoidElements:
  /** The 14 HTML void element tag names (canonical, per the HTML spec). */
  val tags: Set[String] =
    Set("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

  /** True if `tag` is an HTML void element (rendered self-closing, no end tag). */
  def isVoid(tag: String): Boolean = tags.contains(tag)
end VoidElements
