package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** when, forEach, forEachSignal, scoped. */
object ReactiveBoundaries extends DocSpec:

  final case class Row(id: String, n: Int) derives Eq

  def doc = page("Reactive boundaries")(
    md"""
Reactive boundaries are first-class AST nodes. The mount engine wires each once; updates patch
only that boundary. Children are built right-to-left so a slot can find its insertion point from
already-populated later siblings.
""",
    section("when")(
      md"""
`when(cond)(body)` mounts/unmounts as a `Squawk[Boolean]` flips. The body is a thunk; rebuilt
fresh on each true-transition so per-render local state stays correct.
""",
      exampleIO {
        for
          open <- sq(true)
        yield E.div(
          E.button(Events.onClick(_ => open.update(!_)), "toggle"),
          when(open)(E.p("visible")),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("forEach")(
      md"""
Keyed-list reconciliation reuses DOM nodes across reorders (focus/caret preserved). The key
function is identity; the render fn builds from a snapshot value.
""",
      exampleIO {
        for items <- sq(Seq("a", "b", "c"))
        yield E.ul(
          forEach(items)(identity)(s => E.li(s)),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("forEachSignal")(
      md"""
Like `forEach`, but each row holds a persistent `Squawk[A]` fed new values; rows never rebuild;
only boundaries bound to changed fields repaint. Prefer this when row UIs are heavy or hold
local state.
""",
      exampleIO {
        for items <- sq(Seq(Row("1", 1), Row("2", 2)))
        yield E.ul(
          forEachSignal(items)(_.id) { (id, _, signal) =>
            E.li(id, ": ", signal.map(_.n.toString))
          },
        )
      }.assert(_ => assertTrue(true)),
    ),
    section("scoped")(
      md"""
`scoped { … }` opens a `zio.Scope` tied to a subtree's lifetime. Finalizers (conduit
subscriptions, rAF cancels, widget teardown) run when the subtree unmounts.
""",
      exampleIO {
        for n <- sq(0)
        yield scoped {
          ZIO.succeed(E.span(n.map(_.toString)))
        }
      }.assert(_ => assertTrue(true)),
    ),
  )
end ReactiveBoundaries
