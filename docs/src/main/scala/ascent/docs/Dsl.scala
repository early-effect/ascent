package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** Elements, attributes, events, and Arg lifting. */
object Dsl extends DocSpec:

  def doc = page("DSL")(
    md"""
Elements, attributes, and children compose through one uniform `apply`. The `Arg` union
auto-lifts: `String` → text, `Squawk[String]` → reactive text, attr key + `Squawk` → reactive
attribute, `Seq` → flattened. Void elements (`E.input`, `E.br`) reject children at compile time.
""",
    section("Elements and attrs")(
      example {
        E.label(
          A.className("title"),
          A.htmlFor("name"),
          "Name",
        )
      }.assert(ui => assertTrue(ui != null)),
      example {
        E.div(
          E.input(A.`type`("text"), A.placeholder("draft")),
          E.br(),
          E.span("after break"),
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("Events")(
      md"""
`Ev.onClick` hands you the typed platform event (`dom.PointerEvent`). For two-way binding there
is the platform-neutral `Events.onInput` / `Events.onKeyDown` with `targetValue` / `key`.
Handlers are ZIO effects. Use `.sync` (`Ev.sync.onDragStart { … }`) when the body must run on
the dispatch stack (`preventDefault`, drag-and-drop).
""",
      exampleIO {
        for draft <- sq("")
        yield E.input(
          A.value(draft),
          Events.onInput(e => draft.set(e.targetValue.getOrElse(""))),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("ARIA")(
      example {
        E.button(Aria.ariaLabel("Close"), Aria.role("button"), "×")
      }.assert(_ => assertTrue(true))
    ),
  )
end Dsl
