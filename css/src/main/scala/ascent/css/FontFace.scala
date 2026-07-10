package ascent.css

import zio.*

/** A `@font-face` rule — a top-level block of font descriptors that registers a custom font with the browser. Unlike a
  * [[CssClass]], it has no class name and no inner rules: just a flat declaration list wrapped in `@font-face { ... }`.
  *
  * Descriptors are [[Declaration]]s. Most descriptors @font-face accepts are also valid CSS properties (font-family,
  * font-weight, font-style, font-display, …) — pass the SAME typed `Styles.foo.bar` objects you'd use in a normal rule
  * body. The descriptors that are
  * @font-face-only
  *   (`src`, `unicode-range`, the `*-override` family, `size-adjust`) are generated into [[FontFaceDescriptors]] from
  *   the webref `@font-face` at-rule descriptor list.
  *
  * Example:
  * {{{
  *   import ascent.css.{FontFace, FontFaceDescriptors, Styles}
  *
  *   val mySerif = FontFace(
  *     Styles.fontFamily("MySerif"),                                         // shared
  *     FontFaceDescriptors.src.url("/fonts/serif.woff2", format = "woff2"),  // FF-only
  *     FontFaceDescriptors.fontDisplay.swap,                                 // FF-only
  *     Styles.fontWeight(400),                                               // shared
  *   )
  *   mySerif.installInto(sink)
  * }}}
  *
  * Installation: a FontFace is a STANDALONE block (not nested), so it installs directly via [[installInto]] like a
  * [[CssClass]]. The sink key is derived from the `font-family` value so two distinct families coexist while installing
  * the same family twice is idempotent.
  */
final case class FontFace(descriptors: Declaration*):

  /** Render the full `@font-face { ... }` block. No surrounding selector, no nesting. */
  def render: String =
    val builder = StringBuilder()
    builder.append("@font-face {\n")
    descriptors.foreach { d =>
      builder.append("  ").append(d.render).append("\n")
    }
    builder.append("}\n")
    builder.result()

  /** Inject this `@font-face` block into the given sink. Idempotent on the font-family value — installing two FontFace
    * blocks with the SAME font-family REPLACES the previous one, while distinct families coexist.
    */
  def installInto(sink: StyleSink): UIO[Unit] =
    sink.append(sinkKey, render)

  /** Sink key: a stable identifier derived from the font-family descriptor value. Without a font-family declaration the
    * block isn't installable in any meaningful way (anonymous
    * @font-face
    *   entries can't be referenced by `font-family: ...` in rules), so we fall back to a hash-based key that still
    *   keeps two distinct anonymous blocks separate.
    */
  private[css] def sinkKey: String =
    descriptors.find(_.name == "font-family") match
      case Some(d) =>
        // Strip surrounding quotes for the key — the family value `"MySerif"` becomes the
        // key `font-face-MySerif` so reviewers see meaningful keys in the captured sink.
        val cleaned = d.value.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'")
        s"font-face-$cleaned"
      case scala.None =>
        s"font-face-${descriptors.map(_.render).mkString("|").hashCode.toHexString}"
end FontFace
