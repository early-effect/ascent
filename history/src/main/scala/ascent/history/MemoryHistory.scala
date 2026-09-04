package ascent.history

import ascent.squawk.{Source, sq}
import zio.*

/** Session stack in a [[Ref]]. `push` always appends (and drops the forward branch); [[ascent.squawk.Eq]] on the
  * location Squawk is what suppresses observer fan-out for an equal URL.
  */
private final class MemoryHistory(
    source: Source[Location],
    stack: Ref[Vector[Location]],
    index: Ref[Int],
) extends History:

  def location: ascent.squawk.Squawk[Location] = source

  def push(to: Location): UIO[Unit] =
    for
      i <- index.get
      _ <- stack.update(s => s.take(i + 1) :+ to)
      _ <- index.set(i + 1)
      _ <- source.set(to)
    yield ()

  def replace(to: Location): UIO[Unit] =
    for
      i <- index.get
      _ <- stack.update(_.updated(i, to))
      _ <- source.set(to)
    yield ()

  def back: UIO[Unit] = go(-1)

  def forward: UIO[Unit] = go(1)

  def go(delta: Int): UIO[Unit] =
    for
      i <- index.get
      s <- stack.get
      j = i + delta
      _ <-
        if j < 0 || j >= s.length then ZIO.unit
        else index.set(j) *> source.set(s(j))
    yield ()

  def length: UIO[Int] = stack.get.map(_.length)

end MemoryHistory

private[history] object MemoryHistory:

  def make(initial: Location): UIO[History] =
    for
      source <- sq(initial)
      stack  <- Ref.make(Vector(initial))
      index  <- Ref.make(0)
    yield MemoryHistory(source, stack, index)

end MemoryHistory
