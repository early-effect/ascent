package ascent.js

import ascent.squawk.Subscription
import zio.*

/** A bag of subscriptions that the binding engine accumulates as it walks the AST.
  *
  * Every reactive boundary, every event listener, and every nested mount registers its subscription here. On unmount
  * (or on rebuild of a dynamic boundary) `cancelAll` cancels them all — that's the central defense against observer
  * leaks. Bags nest naturally: a dynamic boundary owns a child `Subscriptions` that the parent's cancels first.
  *
  * Entirely DOM-agnostic (a pure `Subscription` bag), so it's identical on every platform.
  */
final class Subscriptions private (subsRef: Ref[Vector[Subscription]]):
  def add(sub: Subscription): UIO[Unit] =
    subsRef.update(_ :+ sub)

  /** Cancel every registered subscription, in reverse registration order (LIFO so children are torn down before their
    * parents — same shape as resource scopes).
    */
  def cancelAll: UIO[Unit] =
    subsRef.getAndSet(Vector.empty).flatMap { subs =>
      ZIO.foreachDiscard(subs.reverse)(_.cancel)
    }
end Subscriptions

object Subscriptions:
  def make: UIO[Subscriptions] = Ref.make(Vector.empty[Subscription]).map(new Subscriptions(_))
