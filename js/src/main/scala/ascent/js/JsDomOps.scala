package ascent.js

import ascent.ast.AscentEvent
import ascent.dom

import scala.scalajs.js

/** [[DomOps]] over the real browser DOM, forwarding to the `@js.native` [[ascent.dom]] facade.
  *
  * This is the whole JS backend — there is NO wrapper type and NO identity cache. Because it operates on RAW facade
  * nodes (`dom.Node`), node identity is exactly browser-node identity: `parentOf(n)` returns the same object the
  * browser holds, so Mount's `sameNode`/reconciliation `eq` checks and the `activeElement` caret guard work with zero
  * bookkeeping. (An earlier design wrapped facade nodes to satisfy dom-core's structural traits and needed a WeakMap
  * identity cache to keep `eq` honest; the [[DomOps]] capability made the wrapper unnecessary, and the cache with it.)
  *
  * Constructed around the ambient [[dom.document]]. `isActive` does the real `document.activeElement eq node` compare —
  * so on JS the controlled-input caret guard is genuinely exercised (unlike the in-memory backend, where it's inert).
  * Events wrap the raw browser event in [[DomEvent]] (rich `.raw` for the typed-event DSL) and register a STABLE
  * `js.Function1` so `removeListener` detaches exactly that registration.
  */
object JsDomOps extends DomOps[dom.Node]:

  private def doc: dom.Document = dom.document

  def createElement(tag: String): dom.Node  = doc.createElement(tag)
  def createText(data: String): dom.Node    = doc.createTextNode(data)
  def createComment(data: String): dom.Node = doc.createComment(data)

  def insert(parent: dom.Node, child: dom.Node, ref: Option[dom.Node]): Unit =
    ref match
      case Some(r) => parent.insertBefore(child, r); ()
      case None    => parent.appendChild(child); ()

  def removeChild(parent: dom.Node, child: dom.Node): Unit =
    parent.removeChild(child); ()

  def parentOf(node: dom.Node): Option[dom.Node] =
    val p = node.parentNode
    if p == null || js.isUndefined(p) then None else Some(p)

  def setAttribute(el: dom.Node, name: String, value: String): Unit =
    el.asInstanceOf[dom.Element].setAttribute(name, value)
  def removeAttribute(el: dom.Node, name: String): Unit =
    el.asInstanceOf[dom.Element].removeAttribute(name)
  def getAttribute(el: dom.Node, name: String): Option[String] =
    val v = el.asInstanceOf[dom.Element].getAttribute(name)
    if v == null || js.isUndefined(v) then None else Some(v)

  def classAdd(el: dom.Node, token: String): Unit    = el.asInstanceOf[dom.Element].classList.add(token)
  def classRemove(el: dom.Node, token: String): Unit = el.asInstanceOf[dom.Element].classList.remove(token)

  /** An element has a live `value` property (for the caret guard) iff it's a form control WITH a caret/selection —
    * input / textarea / select. Detected NOMINALLY by tag name, matching [[InMemoryDomOps.hasValueProperty]] exactly,
    * so the two backends agree. A purely-structural `!isUndefined(el.value)` check would also fire for
    * `<button>`/`<option>`/`<progress>`/`<li>`/… (which expose a `value` property too) and take the property path on JS
    * while the in-memory backend takes the attribute path — an observable cross-backend divergence for
    * `E.button(value := …)` etc. Those non-caret elements fall through to `setAttribute`, so `value` behaves like a
    * normal content attribute (what SSR serialization and `getAttribute` expect).
    */
  def hasValueProperty(el: dom.Node): Boolean =
    val tag = el.asInstanceOf[dom.Element].tagName.toLowerCase
    tag == "input" || tag == "textarea" || tag == "select"

  def getValueProperty(el: dom.Node): String =
    val v = el.asInstanceOf[js.Dynamic].value
    if js.isUndefined(v) || v == null then "" else v.asInstanceOf[String]

  def setValueProperty(el: dom.Node, value: String): Unit =
    el.asInstanceOf[js.Dynamic].value = value

  def setCheckedProperty(el: dom.Node, checked: Boolean): Unit =
    // Nominal (input-only), matching InMemoryDomOps — `checked` is a live property only on <input>. A
    // structural `!isUndefined(el.checked)` check agrees for the real element universe, but keeping it
    // nominal makes the two backends provably identical and avoids a future footgun on some exotic element.
    if el.asInstanceOf[dom.Element].tagName.toLowerCase == "input" then el.asInstanceOf[js.Dynamic].checked = checked

  def setTextData(textNode: dom.Node, data: String): Unit =
    textNode.asInstanceOf[js.Dynamic].data = data

  def isActive(node: dom.Node): Boolean =
    val active = doc.activeElement
    active != null && (active.asInstanceOf[js.Any] eq node.asInstanceOf[js.Any])

  def addListener(el: dom.Node, eventType: String, handler: AscentEvent => Unit): DomOps.ListenerToken =
    // Build the stable js.Function ONCE — a fresh lambda at each use site would be a different function
    // object and removeEventListener would silently no-op. The browser event is wrapped in the rich
    // DomEvent (so `.raw` casts up to dom.PointerEvent etc. in the typed-event DSL).
    val listener: js.Function1[dom.Event, Unit] = (raw: dom.Event) =>
      handler(new DomEvent(raw.asInstanceOf[js.Dynamic]))
    el.asInstanceOf[dom.Element].addEventListener(eventType, listener)
    JsDomOps.FnToken(listener)

  def removeListener(el: dom.Node, eventType: String, token: DomOps.ListenerToken): Unit =
    token match
      case JsDomOps.FnToken(listener) => el.asInstanceOf[dom.Element].removeEventListener(eventType, listener)
      case _                          => ()

  def documentElement: dom.Node = doc.documentElement
  def body: Option[dom.Node]    =
    val b = doc.asInstanceOf[js.Dynamic].body
    if b == null || js.isUndefined(b) then None else Some(b.asInstanceOf[dom.Node])

  def sameNode(a: dom.Node, b: dom.Node): Boolean = a.asInstanceOf[js.Any] eq b.asInstanceOf[js.Any]

  /** Carries the exact `js.Function1` registered with the browser so `removeListener` detaches THAT registration
    * (removeEventListener matches on function identity).
    */
  private final case class FnToken(listener: js.Function1[dom.Event, Unit]) extends DomOps.ListenerToken

  /** The ambient given so `Mount.mount(ui, parent, sink)` resolves `DomOps[dom.Node]` on JS — `import
    * ascent.js.JsDomOps.given` (or it's in implicit scope as a member of this object where referenced).
    */
  given DomOps[dom.Node] = this
end JsDomOps
