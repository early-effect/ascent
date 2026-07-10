package ascent.js

import ascent.ast.AscentEvent
import zio.*

import scala.scalajs.js

/** A js-side implementation of the platform-neutral [[AscentEvent]] backed by a real DOM event.
  *
  * The mount wraps every dispatched event in this so the AST-side handler (typed `AscentEvent => UIO[Unit]`) gets a
  * small, stable surface — `preventDefault`, `targetValue`, `key` — without leaking dom types into core. Reaching for
  * the underlying browser-typed event (e.g. for `clientX` on a MouseEvent) is the typed-event-DSL's job, not this
  * layer's.
  */
final class DomEvent(rawEvent: js.Dynamic) extends AscentEvent:
  def preventDefault: UIO[Unit] = ZIO.succeed(rawEvent.preventDefault())

  /** Eager — calls the real DOM `preventDefault` immediately. Because the mount runs a handler's synchronous prefix
    * inline (runOrFork), invoking this in a handler body fires DURING event dispatch, which is what drag-and-drop /
    * submit / link interception require.
    */
  def preventDefaultNow(): Unit = rawEvent.preventDefault()

  def stopPropagationNow(): Unit = rawEvent.stopPropagation()

  def targetValue: Option[String] =
    val t = rawEvent.target
    if t == null || js.isUndefined(t) then None
    else
      val v = t.asInstanceOf[js.Dynamic].value
      if js.isUndefined(v) || v == null then None
      else Some(v.asInstanceOf[String])

  def key: Option[String] =
    val k = rawEvent.key
    if js.isUndefined(k) || k == null then None
    else Some(k.asInstanceOf[String])

  def targetTag: Option[String] =
    val t = rawEvent.target
    if t == null || js.isUndefined(t) then None
    else
      val tag = t.asInstanceOf[js.Dynamic].tagName
      if js.isUndefined(tag) || tag == null then None
      else Some(tag.asInstanceOf[String].toLowerCase)

  /** The underlying real DOM event. The typed-event DSL casts this to the spec-correct subtype (e.g. `dom.PointerEvent`
    * for click, `dom.KeyboardEvent` for keydown) so user handlers get a typed surface without dropping to `js.Dynamic`.
    */
  def raw: Any = rawEvent
end DomEvent
