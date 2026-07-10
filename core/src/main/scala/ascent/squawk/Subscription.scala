package ascent.squawk

import zio.*

/** A handle to a registered observer. `cancel` detaches it; calling `cancel` more than once is a no-op (idempotent).
  * The leak-safety story rests on this — every observer the binding engine registers gets its `Subscription` collected
  * into a `Subscriptions` bag, then `cancelAll` on unmount.
  */
trait Subscription:
  /** Detach the observer. Idempotent: safe to call multiple times. */
  def cancel: UIO[Unit]

object Subscription:
  /** A no-op subscription (already cancelled / nothing to detach). */
  val empty: Subscription = new Subscription:
    def cancel: UIO[Unit] = ZIO.unit

  /** Build a subscription from a thunk that runs at most once. */
  def make(action: => UIO[Unit]): UIO[Subscription] =
    Ref.make(false).map { done =>
      new Subscription:
        def cancel: UIO[Unit] =
          done.getAndSet(true).flatMap(wasDone => if wasDone then ZIO.unit else action)
    }
end Subscription
