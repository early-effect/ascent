package ascent.squawk

import zio.*

/** A reactive value-over-time.
  *
  * Aviation metaphor: a transponder broadcasts a SQUAWK code that ATC observes; here a `Squawk[A]` is a value that is
  * set/derived and continuously broadcast to observers. ZIO-based, push-driven, glitch-guarded by [[Eq]]: setting an
  * `Eq`-equal value is a no-op so observers never see a spurious update.
  *
  * Three flavours:
  *   - [[Source]] — a mutable cell, created via [[ascent.squawk.sq]]. The only kind that's an effect to construct (it
  *     allocates a `Ref`).
  *   - [[Derived]] — a read-only view built lazily via `map` / [[Squawk.zipWith]]. No constructor effect, so the DSL
  *     stays clean: `E.span(count.map(_.toString))` reads naturally.
  *   - Constants — created via [[Squawk.const]] for the rare static-as-Squawk position.
  *
  * The DOM binding engine drives the whole reactive graph from `observe`. Pure derived values are not "live" until
  * something observes them — that's the engine's job. This keeps allocation cheap and lets you build derived chains
  * freely.
  */
trait Squawk[+A]:
  /** Synchronously read the current value (effectful only because it reads a `Ref`). */
  def get: UIO[A]

  /** Register an observer that fires on every distinct change. The returned subscription detaches the observer when
    * cancelled. The observer's effect runs on the same fiber that called `set`.
    */
  def observe(f: A => UIO[Any]): UIO[Subscription]

  /** Build a derived squawk by mapping each value through `f`. Pure: no effect runs at construction; the derived only
    * attaches to its source when something observes it.
    */
  def map[B](f: A => B)(using Eq[B]): Squawk[B] = Squawk.derived(this)(f)

  /** Internal: how many observers are currently attached. Used by leak-check tests. */
  private[ascent] def observerCount: UIO[Int]

end Squawk

/** A mutable [[Squawk]]. Only this type carries `set`/`update` — the read-only view is `Squawk[A]`. */
final class Source[A] private[squawk] (
    valueRef: Ref[A],
    observersRef: Ref[Vector[A => UIO[Any]]],
)(using eq: Eq[A])
    extends Squawk[A]:

  def get: UIO[A] = valueRef.get

  /** Replace the value. If `eq` says the new value matches the current, this is a no-op (no fan-out, no observer
    * firing). Otherwise observers are notified in registration order on the same fiber.
    */
  def set(a: A): UIO[Unit] =
    valueRef
      .modify { current =>
        if eq.eqv(current, a) then (false, current)
        else (true, a)
      }
      .flatMap { changed =>
        if !changed then ZIO.unit
        else
          // Snapshot the observers BEFORE notifying. If an observer registers another observer
          // during its own callback, the new one must NOT fire on this same change - it sees the
          // next one. Snapshot semantics give that for free.
          // `.exit.unit` swallows BOTH typed failures and defects so one buggy observer never
          // corrupts the rest. (`.ignore` only handles typed failures, not ZIO.die.)
          observersRef.get.flatMap(obs => ZIO.foreachDiscard(obs)(f => f(a).exit.unit))
      }

  /** Functional update: read, apply `f`, write. Equivalent to `for v <- get; _ <- set(f(v)) yield ()`. */
  def update(f: A => A): UIO[Unit] = valueRef.get.flatMap(v => set(f(v)))

  def observe(f: A => UIO[Any]): UIO[Subscription] =
    observersRef.update(_ :+ f) *>
      Subscription.make(observersRef.update(_.filterNot(_ eq f)))

  private[ascent] def observerCount: UIO[Int] = observersRef.get.map(_.size)

end Source

object Squawk:
  /** A constant squawk that never changes; observe is a no-op. Useful for places where the DSL expects a `Squawk[A]`
    * but the value is static.
    */
  def const[A](a: A): Squawk[A] = new Squawk[A]:
    def get: UIO[A]                                  = ZIO.succeed(a)
    def observe(f: A => UIO[Any]): UIO[Subscription] = ZIO.succeed(Subscription.empty)
    private[ascent] def observerCount: UIO[Int]      = ZIO.succeed(0)

  /** Pure derived squawk: applies `f` on demand, propagates only on real (Eq) change of the mapped value. No
    * constructor effect — attaches to the source lazily on first observe.
    */
  def derived[A, B](src: Squawk[A])(f: A => B)(using Eq[B]): Squawk[B] =
    new Squawk[B]:
      def get: UIO[B]                                  = src.get.map(f)
      def observe(g: B => UIO[Any]): UIO[Subscription] =
        for
          // Track the last seen mapped value so we only forward distinct changes.
          last  <- src.get.map(f).flatMap(b => Ref.make(b))
          inner <- src.observe { a =>
            val nb = f(a)
            last.getAndSet(nb).flatMap(prev => if summon[Eq[B]].eqv(prev, nb) then ZIO.unit else g(nb).exit.unit)
          }
        yield inner
      private[ascent] def observerCount: UIO[Int] = src.observerCount

  /** Combine two squawks into a derived squawk. Re-evaluates `f` whenever either side changes, with the same Eq dedup
    * as `derived`. The resulting squawk is glitch-free for the diamond case (one Source feeding two Derived feeding one
    * combiner) because both inner observers read the same final source value.
    */
  def zipWith[A, B, C](a: Squawk[A], b: Squawk[B])(f: (A, B) => C)(using Eq[C]): Squawk[C] =
    new Squawk[C]:
      def get: UIO[C]                                  = a.get.zipWith(b.get)(f)
      def observe(g: C => UIO[Any]): UIO[Subscription] =
        for
          last <- get.flatMap(Ref.make(_))
          forward = (_: Any) =>
            for
              av <- a.get
              bv <- b.get
              nc = f(av, bv)
              prev <- last.getAndSet(nc)
              _    <- if summon[Eq[C]].eqv(prev, nc) then ZIO.unit else g(nc).exit.unit
            yield ()
          subA     <- a.observe(forward)
          subB     <- b.observe(forward)
          combined <- Subscription.make(subA.cancel *> subB.cancel)
        yield combined
      private[ascent] def observerCount: UIO[Int] =
        a.observerCount.zipWith(b.observerCount)(_ + _)
end Squawk

/** The canonical entry point for creating a mutable [[Source]]. ZIO-effectful because it allocates a `Ref`; using `sq`
  * (rather than `Source.apply`) keeps the call sites short: `for count <- sq(0) yield E.div(...)`.
  */
def sq[A](init: A)(using eq: Eq[A]): UIO[Source[A]] =
  for
    valueRef     <- Ref.make(init)
    observersRef <- Ref.make(Vector.empty[A => UIO[Any]])
  yield new Source[A](valueRef, observersRef)
