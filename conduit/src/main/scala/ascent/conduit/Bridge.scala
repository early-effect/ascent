package ascent.conduit

import _root_.conduit.{Conduit, Lens}
import ascent.squawk.{Eq, Squawk, sq}
import zio.*

/** Bridge between conduit's lens-keyed listener model and ascent's [[Squawk]] reactive primitive.
  *
  * `c.squawk(lens)` returns a `Squawk[S]` tracking that slice of the model. Squawk mutations are infallible, so the
  * listener adds no failure modes; a failing conduit run surfaces through conduit's own `run` boundary, not via Squawk.
  */
extension [M, E](c: Conduit[M, E])

  /** A read-only Squawk that mirrors the lens slice of the conduit model. */
  def squawk[S](lens: Lens[M, S])(using Eq[S]): UIO[Squawk[S]] =
    bridge(c, lens)

/** Wiring shared by the [[squawk]] extension and [[Ctx]]. Leaves the listener registered for the conduit's lifetime —
  * correct for component-lifetime slices that live as long as the whole view.
  */
private[conduit] def bridge[M, E, S](c: Conduit[M, E], lens: Lens[M, S])(using Eq[S]): UIO[Squawk[S]] =
  for
    seed <- c.zoom(lens)
    src  <- sq(seed)
    _    <- c.subscribe(lens)(s => src.set(s))
  yield src

/** Same wiring, but unsubscribes when the ambient [[zio.Scope]] closes — the leak-free form for element subscriptions
  * inside dynamic boundaries (a `key(id)` slice in a [[ascent.ast.UI.ForEach]] row), so a long-lived list of
  * short-lived rows doesn't accumulate dead listeners.
  */
private[conduit] def scopedBridge[M, S](c: Conduit[M, Nothing], lens: Lens[M, S])(using
    Eq[S]
): URIO[Scope, Squawk[S]] =
  for
    seed     <- c.zoom(lens)
    src      <- sq(seed)
    listener <- c.subscribe(lens)(s => src.set(s))
    _        <- ZIO.addFinalizer(c.unsubscribe(listener))
  yield src
