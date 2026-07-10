package ascent.css

/** A composable CSS `font-family` value — a prioritized, comma-separated list of family names with a generic family
  * (`serif`, `sans-serif`, …) as the final fallback.
  *
  * A named family containing whitespace or punctuation must be quoted (`"Times New Roman"`); a generic family must NOT
  * be quoted. [[FontFamily.named]] auto-quotes when needed; the `serif`/`sansSerif`/… vals are the unquoted generics.
  * Build a stack with [[FontFamily.of]]. Attaches via the universal `apply(CssValue)` overload.
  */
sealed trait FontFamily extends CssValue:
  override def toString: String = render

object FontFamily:

  /** One entry in a font stack: a quoted family name or an unquoted generic. */
  final case class Single(rendered: String) extends FontFamily:
    def render: String = rendered

  /** A prioritized comma-separated stack. */
  final case class Stack(families: List[FontFamily]) extends FontFamily:
    def render: String = families.map(_.render).mkString(", ")

  /** A named family. Quotes the name when it isn't a bare CSS identifier (has spaces, digits-first, punctuation), per
    * the `font-family` grammar; an already-quoted name is left as-is.
    */
  def named(name: String): FontFamily =
    val trimmed = name.trim
    if trimmed.startsWith("\"") || trimmed.startsWith("'") then Single(trimmed)
    else if trimmed.matches("[a-zA-Z_-][a-zA-Z0-9_-]*") then Single(trimmed)
    else Single("\"" + trimmed + "\"")

  // Generic families — unquoted keywords, the spec-defined fallbacks.
  val serif: FontFamily     = Single("serif")
  val sansSerif: FontFamily = Single("sans-serif")
  val monospace: FontFamily = Single("monospace")
  val cursive: FontFamily   = Single("cursive")
  val fantasy: FontFamily   = Single("fantasy")
  val systemUi: FontFamily  = Single("system-ui")

  /** Build a font stack from entries — strings are taken as named families (auto-quoted), generics as themselves:
    * `FontFamily.of("Inter", FontFamily.systemUi, FontFamily.sansSerif)`.
    */
  def of(families: FontFamily*): FontFamily = Stack(families.toList)

  /** Convenience: build a stack from raw name strings (each auto-quoted). */
  def of(first: String, rest: String*): FontFamily = Stack((first +: rest).map(named).toList)
end FontFamily
