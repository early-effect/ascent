package ascent.domcore

import ascent.domcore.generated.{Event, EventTarget}

/** Real mutable dispatch state for [[ascent.domcore.generated.Event]] — the flags the WHATWG dispatch algorithm (in
  * [[EventTargetOverrides.dispatchEvent]]) reads and writes as it walks the tree: `target`/`currentTarget`, the
  * `defaultPrevented` flag (set by `preventDefault` only when `cancelable`), and the two propagation-stop flags.
  * `initEvent` is the in-memory constructor (`EventMemory()` then `initEvent(type, bubbles, cancelable)`).
  *
  * `EventMemory` is parentless (an `Event` is a sibling of `Node` under `EventTarget`, NOT a tree node), so this state
  * lives in the trait itself rather than on `NodeMemoryBase`. The dispatch loop reaches it by matching the incoming
  * `Event` back to `EventOverrides` — hence the `private[domcore]` (not `private`) mutation hooks.
  */
trait EventOverrides:
  self: Event =>

  private var _type: String                                 = ""
  private var _bubbles: Boolean                             = false
  private var _cancelable: Boolean                          = false
  private var _defaultPrevented: Boolean                    = false
  private[domcore] var targetRef: EventTarget | Null        = null
  private[domcore] var currentTargetRef: EventTarget | Null = null
  private[domcore] var propagationStopped: Boolean          = false
  private[domcore] var immediateStopped: Boolean            = false

  def `type`: String             = _type
  def bubbles: Boolean           = _bubbles
  def cancelable: Boolean        = _cancelable
  def defaultPrevented: Boolean  = _defaultPrevented
  def target: EventTarget        = targetRef
  def currentTarget: EventTarget = currentTargetRef

  def initEvent(`type`: String, bubbles: Boolean, cancelable: Boolean): Unit =
    _type = `type`
    _bubbles = bubbles
    _cancelable = cancelable

  /** Sets the canceled flag — but ONLY for a cancelable event, per spec (preventDefault on a non-cancelable event is a
    * no-op, and `dispatchEvent` still returns true).
    */
  def preventDefault(): Unit =
    if _cancelable then _defaultPrevented = true

  def stopPropagation(): Unit          = propagationStopped = true
  def stopImmediatePropagation(): Unit =
    propagationStopped = true
    immediateStopped = true
end EventOverrides
