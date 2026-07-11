package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** The reactive primitive: sources, derived maps, Eq dedup, subscriptions. */
object SquawkPage extends DocSpec:

  def doc = page("Squawk")(
    md"""
A `Squawk[A]` is a value-over-time. Mutable sources are created effectfully; `map` / `zipWith` are
**pure and lazy**; derived squawks are not live until something observes them. Changes are
deduped by a pluggable `Eq[A]` (an Eq-equal write is a no-op).
""",
    section("Sources and updates")(
      md"""
`sq(init)` allocates a `Source[A]`. `set` / `update` / `observe` are effects. Every `observe`
returns a `Subscription`; the mount engine collects those into cleanup and tears them down LIFO.
""",
      exampleZIO {
        for
          n   <- sq(0)
          _   <- n.set(1)
          _   <- n.update(_ + 1)
          cur <- n.get
        yield cur
      }.assert(n => assertTrue(n == 2)),
    ),
    section("Eq dedup")(
      md"""
An Eq-equal write does not notify observers; no spurious repaints.
""",
      exampleZIO {
        for
          n     <- sq(0)
          fired <- Ref.make(0)
          _     <- n.observe(_ => fired.update(_ + 1).unit)
          _     <- n.set(0)
          _     <- n.set(1)
          count <- fired.get
        yield count
      }.assert(c => assertTrue(c == 1)),
    ),
    section("Derived squawks")(
      md"""
`map` and `zipWith` stay lazy. `zipWith` reads both sources at observe-time, which keeps diamond
updates glitch-free.
""",
      exampleIO {
        for
          a <- sq(1)
          b <- sq(10)
          sum = Squawk.zipWith(a, b)(_ + _)
        yield E.span(sum.map(_.toString))
      }.assert(_ => assertTrue(true)),
    ),
    section("In the tree")(
      md"""
A `Squawk[String]` child becomes reactive text; an attr key applied to a `Squawk` becomes a
reactive attribute; all via the DSL's `Arg` lifting.
""",
      exampleIO {
        for label <- sq("hello")
        yield E.p(A.className(label.map(s => s"x-$s")), label)
      }.interactive.assert(_ => assertTrue(true)),
    ),
  )
end SquawkPage
