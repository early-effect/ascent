package ascent.css

import zio.*

/** A `@page` rule — a top-level descriptor block controlling paged-media behaviour (printing, paged overflow contexts).
  * Same architectural shape as [[FontFace]]: a flat declaration list wrapped in `@page { ... }`, no inner rules, no
  * nesting under a selector.
  *
  * Descriptors are [[Declaration]]s. Most properties used inside `@page` (`margin`, `padding`, `font-size`) are general
  * CSS — pass typed `Styles.foo.bar` objects as-is. Page-only descriptors (`size`, `bleed`, `marks`,
  * `page-orientation`, `page-margin-safety`) are surfaced via the generated [[PageDescriptors]] catalog.
  *
  * Example:
  * {{{
  *   import ascent.css.{Page, PageDescriptors, Styles}
  *
  *   val printPage = Page(
  *     PageDescriptors.size.auto,                 // @page-only
  *     PageDescriptors.pageOrientation.upright,   // @page-only
  *     Styles.margin("1in"),                      // shared with rule bodies
  *   )
  *   printPage.installInto(sink)
  * }}}
  */
final case class Page(descriptors: Declaration*):

  def render: String =
    val builder = StringBuilder()
    builder.append("@page {\n")
    descriptors.foreach { d =>
      builder.append("  ").append(d.render).append("\n")
    }
    builder.append("}\n")
    builder.result()

  /** Inject this `@page` block into the given sink under a stable key. `@page` is anonymous (no name slot like
    * `@counter-style` has), so the key is just `"@page"` — installing twice replaces the previous block. Authors who
    * need multiple distinct @page blocks (a v2 use case once page-selector pseudos like `:first` / `:left` are typed)
    * can use a named override.
    */
  def installInto(sink: StyleSink): UIO[Unit] = sink.append(sinkKey, render)

  /** Sink key. `@page` is anonymous (no name slot), so the key is the literal `"@page"` — installing twice replaces. */
  private[css] def sinkKey: String = "@page"
end Page
