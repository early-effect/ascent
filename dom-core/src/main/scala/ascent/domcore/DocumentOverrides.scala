package ascent.domcore

import ascent.css.Sel
import ascent.domcore.generated.{Comment, Document, Element, ElementFactory, HTMLElement, HTMLHeadElement, Node, Text}

// `Matchable[Element]` lives in ElementOverrides' companion object — see the note there for why
// implicit search doesn't find it automatically here.
import ElementOverrides.given_Matchable_Element

/** Real creation/navigation behavior for [[ascent.domcore.generated.Document]] — the whole point of an in-memory
  * backend. `createElement` dispatches by tag name through [[ElementFactory]] (the SAME table
  * [[NodeOverrides.cloneNode]] uses); `body`/`head`/`documentElement`/`getElementById`/the `querySelector` family are
  * real tree queries over the SAME `childList` every other `Node` override already walks.
  *
  * `createElement`'s real WebIDL `options` param (`DOMString or ElementCreationOptions`) is ignored — it only concerns
  * `is:` custom-element upgrade, which this in-memory tree has no custom-element registry to honor.
  */
trait DocumentOverrides:
  self: NodeMemoryBase & Document =>

  // A Document is constructed directly (`DocumentMemory()`), not through ElementFactory, so its node
  // identity is fixed here rather than stamped: DOCUMENT_NODE (9) and the "#document" sentinel. These
  // override the generic nodeType/nodeName that NodeOverrides reads from the (unstamped) refs.
  override def nodeType: Int    = 9
  override def nodeName: String = "#document"

  def createElement(localName: String, options: String | PlatformOpaque): Element =
    val e = ElementFactory.createByTag(localName)
    e match
      case eb: NodeMemoryBase => eb.ownerDocumentRef = self
      case _                  => ()
    e

  def createTextNode(data: String): Text =
    val t = ElementFactory.createText(data)
    t match
      case tb: NodeMemoryBase => tb.ownerDocumentRef = self
      case _                  => ()
    t

  def createComment(data: String): Comment =
    val c = ElementFactory.createComment(data)
    c match
      case cb: NodeMemoryBase => cb.ownerDocumentRef = self
      case _                  => ()
    c

  def documentElement: Element =
    self.childList.collectFirst { case e: Element => e }.getOrElse(null)

  def body: HTMLElement =
    Option(documentElement)
      .flatMap(html => DocumentOverrides.childElements(html).find(e => e.tagName == "body" || e.tagName == "frameset"))
      .map(_.asInstanceOf[HTMLElement])
      .getOrElse(null)

  def body_=(value: HTMLElement): Unit =
    val html = documentElement
    if html != null then
      val old = body
      if old != null then html.replaceChild(value, old)
      else html.appendChild(value)
    ()

  def head: HTMLHeadElement =
    Option(documentElement)
      .flatMap(html => DocumentOverrides.childElements(html).find(_.tagName == "head"))
      .map(_.asInstanceOf[HTMLHeadElement])
      .getOrElse(null)

  /** No interactive focus exists off a real browser — `None`, matching the plan's decision on `activeElement`, is the
    * honest answer for an in-memory tree, not a stand-in for missing behavior.
    */
  def activeElement: Element = null

  def getElementById(elementId: String): Element =
    Option(documentElement)
      .flatMap(html => (html +: DocumentOverrides.descendantElements(html)).find(_.getAttribute("id") == elementId))
      .getOrElse(null)

  def querySelector(selectors: String): Element =
    Option(documentElement)
      .flatMap { html =>
        Sel.parse(selectors) match
          case Left(_)    => None
          case Right(sel) => (html +: DocumentOverrides.descendantElements(html)).find(e => sel.matches(e))
      }
      .getOrElse(null)

  def querySelectorAll(selectors: String): ascent.domcore.generated.NodeList =
    val matched: Seq[Node] = Option(documentElement).toList.flatMap { html =>
      Sel.parse(selectors) match
        case Left(_)    => Nil
        case Right(sel) => (html +: DocumentOverrides.descendantElements(html)).filter(e => sel.matches(e))
    }
    NodeListView(() => matched)
end DocumentOverrides

object DocumentOverrides:
  private def childElements(e: Element): Seq[Element] =
    e match
      case nb: NodeMemoryBase => nb.childList.collect { case c: Element => c }.toSeq
      case _                  => Nil

  private def descendantElements(e: Element): Seq[Element] =
    childElements(e).flatMap(c => c +: descendantElements(c))
end DocumentOverrides
