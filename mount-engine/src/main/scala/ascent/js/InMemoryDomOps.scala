package ascent.js

import ascent.ast.AscentEvent
import ascent.domcore.generated.{
  Document,
  Element,
  Event,
  HTMLInputElement,
  HTMLSelectElement,
  HTMLTextAreaElement,
  Node,
  Text,
}

/** [[DomOps]] over dom-core's platform-neutral in-memory kernel. Runs on jvm/js/native, so the whole [[Mount]] suite
  * exercises the engine browser-free.
  *
  * Constructed around a [[Document]] (dom-core has no global one), the source of `createElement`/`createTextNode`/
  * `createComment`. Node identity is real reference identity on the kernel instances. `isActive` is always `false` — an
  * in-memory tree has no interactive focus (the caret guard's skip branch is browser-only, by design). Events go
  * through the kernel's real [[Event]] dispatch (`addEventListener`/`dispatchEvent` with WHATWG bubbling), converting
  * each dispatched [[Event]] into a synthetic [[AscentEvent]].
  */
final class InMemoryDomOps(doc: Document) extends DomOps[Node]:

  def createElement(tag: String): Node  = doc.createElement(tag, "")
  def createText(data: String): Node    = doc.createTextNode(data)
  def createComment(data: String): Node = doc.createComment(data)

  def insert(parent: Node, child: Node, ref: Option[Node]): Unit =
    ref match
      case Some(r) => parent.insertBefore(child, r); ()
      case None    => parent.appendChild(child); ()

  def removeChild(parent: Node, child: Node): Unit =
    parent.removeChild(child); ()

  def parentOf(node: Node): Option[Node] = Option(node.parentNode)

  def setAttribute(el: Node, name: String, value: String): Unit = el.asInstanceOf[Element].setAttribute(name, value)
  def removeAttribute(el: Node, name: String): Unit             = el.asInstanceOf[Element].removeAttribute(name)
  def getAttribute(el: Node, name: String): Option[String]      = Option(el.asInstanceOf[Element].getAttribute(name))

  def classAdd(el: Node, token: String): Unit    = el.asInstanceOf[Element].classList.add(token)
  def classRemove(el: Node, token: String): Unit = el.asInstanceOf[Element].classList.remove(token)

  def hasValueProperty(el: Node): Boolean =
    el.isInstanceOf[HTMLInputElement] || el.isInstanceOf[HTMLTextAreaElement] || el.isInstanceOf[HTMLSelectElement]

  def getValueProperty(el: Node): String = el match
    case i: HTMLInputElement    => i.value
    case t: HTMLTextAreaElement => t.value
    case s: HTMLSelectElement   => s.value
    case _                      => ""

  def setValueProperty(el: Node, value: String): Unit = el match
    case i: HTMLInputElement    => i.value = value
    case t: HTMLTextAreaElement => t.value = value
    case s: HTMLSelectElement   => s.value = value
    case _                      => ()

  def setCheckedProperty(el: Node, checked: Boolean): Unit = el match
    case i: HTMLInputElement => i.checked = checked
    case _                   => ()

  def setTextData(textNode: Node, data: String): Unit = textNode.asInstanceOf[Text].data = data

  /** No interactive focus in an in-memory tree — always false, so the caret guard always writes. */
  def isActive(node: Node): Boolean = false

  def addListener(el: Node, eventType: String, handler: AscentEvent => Unit): DomOps.ListenerToken =
    // Register a domcore Event => Unit that converts the dispatched event to a synthetic AscentEvent.
    // The Scala closure IS the token (removeEventListener matches it by reference via the kernel's -=).
    val listener: Event => Unit = (ev: Event) => handler(InMemoryDomOps.toAscent(ev))
    el.addEventListener(eventType, listener, false)
    InMemoryDomOps.FnToken(listener)

  def removeListener(el: Node, eventType: String, token: DomOps.ListenerToken): Unit =
    token match
      case InMemoryDomOps.FnToken(listener) => el.removeEventListener(eventType, listener, false)
      case _                                => ()

  def documentElement: Node = doc.documentElement
  def body: Option[Node]    = Option(doc.body)

  def sameNode(a: Node, b: Node): Boolean = a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]
end InMemoryDomOps

object InMemoryDomOps:
  /** The listener token carries the exact `Event => Unit` closure registered, so `removeListener` detaches THAT
    * registration by reference (the kernel's `removeEventListener` matches on function identity).
    */
  private final case class FnToken(listener: Event => Unit) extends DomOps.ListenerToken

  /** A synthetic [[AscentEvent]] over an in-memory dom-core [[Event]] — `preventDefaultNow`/`stopPropagationNow` mutate
    * the event's real flags (so a test can assert `defaultPrevented`); the value/key/tag accessors aren't carried by
    * the bare structural Event (no `target.value` on the neutral surface), so they're `None`. `raw` is the Event.
    */
  private def toAscent(ev: Event): AscentEvent =
    AscentEvent.simple(
      onPreventDefaultNow = () => ev.preventDefault(),
      onStopPropagationNow = () => ev.stopPropagation(),
      preventDefaultEffect = zio.ZIO.succeed(ev.preventDefault()),
      raw = ev,
    )

  /** Build the capability + a fresh in-memory document. Tests and SSR call this to get a `given DomOps[Node]`. */
  def make(): (Document, DomOps[Node]) =
    val doc = ascent.domcore.generated.DocumentMemory()
    (doc, new InMemoryDomOps(doc))
end InMemoryDomOps
