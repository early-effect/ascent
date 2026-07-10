package ascent.domcore

import ascent.domcore.generated.{Event, EventTarget}

/** Real listener storage + dispatch for [[ascent.domcore.generated.EventTarget]], mixed into the generated
  * `EventTargetMemory` class (see `Renderer.memoryImpls`'s `handWrittenOverrides` mechanism). Stores listeners in
  * [[NodeMemoryBase.listeners]] so every `Node` (which extends `EventTarget`) shares the same registry its own
  * generated fields live alongside.
  *
  * `options` is the real `AddEventListenerOptions or boolean` union per spec (see
  * `ascent.domgen.DefBuilder.structuralType`'s union-splitting: `AddEventListenerOptions` isn't part of the portable
  * structural surface so it resolves to [[PlatformOpaque]], `boolean` resolves to `Boolean`) and is ignored either way:
  * `once`/`capture`/`passive` semantics need a real browser event-dispatch phase model this in-memory tree doesn't
  * have. `dispatchEvent` synchronously invokes every registered listener for the event's `type`, in registration order
  * — no capture/bubble phases, no default actions — since there is no ancestor-relative dispatch semantic to model
  * without a real browser's event flow.
  */
trait EventTargetOverrides:
  self: NodeMemoryBase =>

  def addEventListener(
      `type`: String,
      callback: Event => Unit,
      @annotation.unused options: PlatformOpaque | Boolean,
  ): Unit =
    self.listeners.getOrElseUpdate(`type`, scala.collection.mutable.ArrayBuffer.empty) += callback

  def removeEventListener(
      `type`: String,
      callback: Event => Unit,
      @annotation.unused options: PlatformOpaque | Boolean,
  ): Unit =
    self.listeners.get(`type`).foreach(_ -= callback)

  /** The WHATWG dispatch algorithm, minus the capture phase (which needs the per-listener `capture` flag the ignored
    * `options` param would carry): set `event.target`, then invoke listeners at the target and — for a `bubbles` event
    * — at each ancestor up the parent chain, innermost-first. `stopPropagation` halts the walk after the current node;
    * `stopImmediatePropagation` also suppresses the current node's remaining listeners. Returns `false` iff the event
    * was canceled (`preventDefault` on a cancelable event), matching the spec's "not canceled" return contract.
    * `currentTarget` is set per node and cleared when dispatch finishes.
    *
    * A listener list is SNAPSHOTTED per node before invoking, so a handler that adds/removes listeners mid- dispatch
    * doesn't perturb the current node's iteration (matches the spec's "copy the listener list" step and avoids a
    * ConcurrentModification-style hazard over the mutable buffer).
    */
  def dispatchEvent(event: Event): Boolean =
    val eo: EventOverrides | Null = event match
      case e: EventOverrides => e
      case _                 => null
    val selfTarget = self match
      case et: EventTarget => et
      case _               => null
    if eo != null then
      eo.targetRef = selfTarget
      eo.propagationStopped = false
      eo.immediateStopped = false

    // The dispatch path: the target node, then its ancestors (only if the event bubbles).
    val path: List[NodeMemoryBase] =
      if eo != null && eo.bubbles then EventTargetOverrides.selfAndAncestors(self)
      else List(self)

    var i = 0
    while i < path.length && !(eo != null && eo.propagationStopped) do
      val node = path(i)
      if eo != null then
        eo.currentTargetRef = node match
          case et: EventTarget => et
          case _               => null
      val snapshot = node.listeners.get(event.`type`).map(_.toList).getOrElse(Nil)
      var j        = 0
      while j < snapshot.length && !(eo != null && eo.immediateStopped) do
        snapshot(j)(event)
        j += 1
      i += 1
    end while

    if eo != null then eo.currentTargetRef = null
    // Return "not canceled": false iff a cancelable event had preventDefault called.
    !(eo != null && eo.defaultPrevented)
  end dispatchEvent
end EventTargetOverrides

object EventTargetOverrides:
  /** The dispatch path for a bubbling event: the target node first, then each ancestor up the `parentRef` chain
    * (innermost-first) — the order listeners fire in the bubble phase.
    */
  private def selfAndAncestors(start: NodeMemoryBase): List[NodeMemoryBase] =
    val acc      = scala.collection.mutable.ListBuffer[NodeMemoryBase](start)
    var cur: Any = start.parentRef
    while cur != null do
      cur match
        case nb: NodeMemoryBase =>
          acc += nb
          cur = nb.parentRef
        case _ => cur = null
    acc.toList
  end selfAndAncestors
end EventTargetOverrides
