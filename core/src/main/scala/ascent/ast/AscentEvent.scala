package ascent.ast

import zio.*

/** A platform-neutral DOM event abstraction.
  *
  * `core` must not import a DOM facade (it's pure and cross-platform), so events use this small abstraction instead of
  * `dom.Event`. The js backend supplies an implementation backed by a real `dom.Event`; an SSR backend simply drops
  * handlers (no events at render time on the server).
  *
  * Members chosen to cover what TodoMVC + counter actually need: `preventDefault`, the input's current value (for
  * two-way binding), and the keyboard key (for Enter-to-submit). The js layer gives users full access to the typed
  * underlying `ascent.dom` event when they need more — this abstraction is the lowest-common-denominator the AST layer
  * commits to.
  */
trait AscentEvent:
  /** Ask the browser not to perform its default action for this event. Effectful so it composes with retries and other
    * ZIO operators if a handler builds a complex pipeline.
    *
    * Timing caveat: this only fires when the RETURNED effect runs. The mount engine runs a handler's synchronous prefix
    * inline (via `runOrFork`), so a `preventDefault` early in a handler that later suspends is fine — but for actions
    * the browser demands strictly during dispatch (drag-and-drop's `dragover`), prefer the eager [[preventDefaultNow]].
    */
  def preventDefault: UIO[Unit]

  /** EAGER `preventDefault` — runs the instant it's called, on the browser's dispatch stack, rather than when a
    * returned effect executes. Use this in a handler BODY for cases the browser requires synchronously: `dragover` (to
    * allow a drop), form `submit` / link-click interception, key suppression. Returns `Unit`, so it reads naturally
    * before the handler's `ZIO` result — `e.preventDefaultNow(); ctx(action)`. A no-op on inert (SSR/test) events.
    */
  def preventDefaultNow(): Unit

  /** EAGER `stopPropagation` — stop the event bubbling, synchronously, from a handler body. Same rationale and timing
    * as [[preventDefaultNow]]. A no-op on inert events.
    */
  def stopPropagationNow(): Unit

  /** For form-control events (input/change), the current `value` of the target element. None for events on non-form
    * targets, or when the target has no `value` property.
    */
  def targetValue: Option[String]

  /** For keyboard events, the `key` value (e.g. `"Enter"`, `"a"`, `"ArrowDown"`). None otherwise. */
  def key: Option[String]

  /** The lowercased tag name of the event target (e.g. `"input"`, `"textarea"`, `"body"`). None when there's no target
    * element. Lets a handler tell "am I typing in a field?" without reaching into the raw DOM event — the essential
    * datum for global keyboard shortcuts that must yield while the user edits text.
    */
  def targetTag: Option[String]

  /** The underlying browser-typed event, opaque from `core`'s perspective.
    *
    * The js backend returns the real `dom.Event` (or a subtype like `dom.PointerEvent`, `dom.KeyboardEvent`); SSR / JVM
    * backends return `null` (or a stub) since events don't fire there. The typed-event DSL in `js` casts this to the
    * spec-correct type so users write `onClick(e => e.clientX)` and get the typed surface.
    */
  def raw: Any
end AscentEvent

object AscentEvent:
  /** A simple data-bearing implementation, used by core unit tests and by SSR (where handlers never actually fire — the
    * AST is the same shape but its events are inert). The js mount supplies its own DOM-backed implementation.
    */
  def simple(
      targetValue: Option[String] = None,
      key: Option[String] = None,
      targetTag: Option[String] = None,
      preventDefaultEffect: UIO[Unit] = ZIO.unit,
      onPreventDefaultNow: () => Unit = () => (),
      onStopPropagationNow: () => Unit = () => (),
      raw: Any = null,
  ): AscentEvent =
    val tv  = targetValue
    val k   = key
    val tt  = targetTag
    val pd  = preventDefaultEffect
    val pdn = onPreventDefaultNow
    val spn = onStopPropagationNow
    val r   = raw
    new AscentEvent:
      def preventDefault: UIO[Unit]   = pd
      def preventDefaultNow(): Unit   = pdn()
      def stopPropagationNow(): Unit  = spn()
      def targetValue: Option[String] = tv
      def key: Option[String]         = k
      def targetTag: Option[String]   = tt
      def raw: Any                    = r
  end simple
end AscentEvent
