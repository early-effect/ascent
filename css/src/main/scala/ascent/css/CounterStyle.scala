package ascent.css

import zio.*

/** A `@counter-style` rule — a top-level descriptor block defining a custom counter presentation (used by
  * `list-style-type` / `counter-reset` / `counter-increment`). Architectural shape: descriptor block with a NAME —
  * `@counter-style my-counter { ... }`.
  *
  * Descriptors come from the generated [[CounterStyleDescriptors]] catalog (none collide with general `Styles`
  * properties, so the only path is via the descriptor catalog).
  *
  * Example:
  * {{{
  *   import ascent.css.{CounterStyle, CounterStyleDescriptors}
  *
  *   val thumbs = CounterStyle("thumbs",
  *     CounterStyleDescriptors.system.cyclic,
  *     CounterStyleDescriptors.symbols("\\"\\\\1F44D\\""),
  *     CounterStyleDescriptors.suffix(" "),
  *   )
  *   thumbs.installInto(sink)
  *   // Then in a rule body: Styles.listStyleType("thumbs")
  * }}}
  *
  * @param name
  *   the counter-style name — the identifier referenced from `list-style-type` and friends. Must be a valid CSS
  *   identifier (the W3C grammar restricts the name to `<custom-ident>`); we don't validate here, only render.
  */
final case class CounterStyle(name: String, descriptors: Declaration*):

  def render: String =
    val builder = StringBuilder()
    builder.append("@counter-style ").append(name).append(" {\n")
    descriptors.foreach { d =>
      builder.append("  ").append(d.render).append("\n")
    }
    builder.append("}\n")
    builder.result()

  /** Inject this `@counter-style` block into the given sink under a key derived from the counter-style name. Two
    * distinct counter-style names coexist; installing the same name twice REPLACES the previous block (idempotent
    * re-injection).
    */
  def installInto(sink: StyleSink): UIO[Unit] =
    sink.append(sinkKey, render)

  /** Sink key derived from the counter-style name. Two names coexist; the same name replaces (idempotent). */
  private[css] def sinkKey: String = s"counter-style-$name"
end CounterStyle
