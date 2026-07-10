package ascent.js

import zio.*

/** In-memory boundary state for a position in the DOM tree.
  *
  * Instead of marking a boundary's range with `<!--start-->...<!--end-->` comment anchors, the runtime keeps a `Slot`
  * per boundary tracking the actual DOM nodes it currently owns. Empty slots own zero nodes — no trace in the DOM.
  *
  * The parent of a slot uses [[firstNode]] to compute insertion points for its earlier siblings: "where do I splice my
  * new content? before the next non-empty slot's first node, or append if every later sibling is empty too."
  *
  * Generic over the backend node type `N` (via the ambient [[DomOps]]), so the same Slot works over the in-memory
  * kernel and the raw JS facade. `removeAll` detaches each owned node from its live parent.
  */
final class Slot[N] private (state: Ref[Vector[N]])(using ops: DomOps[N]):
  def nodes: UIO[Vector[N]] = state.get

  def firstNode: UIO[Option[N]] = state.get.map(_.headOption)

  /** Update the slot's view only — the caller attaches the new nodes (and removed any old ones). */
  def setNodes(newNodes: Vector[N]): UIO[Unit] = state.set(newNodes)

  def removeAll(): UIO[Unit] =
    state.getAndSet(Vector.empty).map { current =>
      current.foreach(n => ops.parentOf(n).foreach(p => ops.removeChild(p, n)))
    }
end Slot

object Slot:
  def make[N](using DomOps[N]): UIO[Slot[N]] = Ref.make(Vector.empty[N]).map(new Slot(_))
