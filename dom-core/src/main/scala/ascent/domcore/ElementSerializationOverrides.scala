package ascent.domcore

import ascent.domcore.generated.{Comment, Document, DocumentType, Element, Text}
import ascent.domtypes.VoidElements

/** Real `innerHTML` / `outerHTML` for the in-memory [[ascent.domcore.generated.Element]]: a genuine DOM feature (a live
  * subtree serialized to a markup string), also the foundation of the SSR path (mount into an in-memory tree, then read
  * `root.innerHTML`).
  *
  * Output is COMPACT (WHATWG fragment-serialization: no incidental whitespace, no newlines): the form a morph/patch
  * consumer diffs, where stray whitespace would show up as spurious text nodes. A separate indented rendering for
  * humans lives in [[Serialize.pretty]]. [[Element]] itself only ever produces the canonical compact form, matching a
  * browser's `outerHTML`.
  *
  *   - attributes emit in the element's stored (insertion) order: `attributeMap` is a `LinkedHashMap`;
  *   - a boolean/presence attribute stored as `""` emits `name=""` (the canonical HTML form);
  *   - void elements (`br`, `input`, …) emit no close tag and no children;
  *   - `Text` nodes escape `& < >`, except under a raw-text parent (`script`, `style`, and the historic raw-text tags),
  *     where the data is literal and a case-insensitive `</` + that tag is spelled `<\/` + the original spelling so the
  *     element is not closed early;
  *   - `Comment` nodes emit `<!--data-->` (data unescaped, per spec: a comment can't contain `-->`, which the in-memory
  *     model never produces);
  *   - a `Document` emits its doctype (when it has one) then its element children, with no extra whitespace;
  *   - attribute values escape via [[HtmlSerialize.escapeAttr]].
  *
  * The setters (`innerHTML_=` / `outerHTML_=`) parse markup back into a tree, which this in-memory model doesn't do, so
  * they stay `???`: an honest gap (nothing in ascent's own usage sets innerHTML on the in-memory backend; the browser
  * backend has the real thing).
  */
trait ElementSerializationOverrides:
  self: NodeMemoryBase & Element =>

  def innerHTML: PlatformOpaque | String =
    ElementSerializationOverrides.serializeChildren(self.childList.toSeq, Some(self.tagName))
  def innerHTML_=(value: PlatformOpaque | String): Unit = ???

  def outerHTML: PlatformOpaque | String                = ElementSerializationOverrides.serializeElement(self)
  def outerHTML_=(value: PlatformOpaque | String): Unit = ???
end ElementSerializationOverrides

object ElementSerializationOverrides:
  /** Serialize one node to its compact markup. `parentTag` selects raw-text handling for a `Text` node. */
  private[domcore] def serializeNode(node: ascent.domcore.generated.Node, parentTag: Option[String] = None): String =
    node match
      case e: Element       => serializeElement(e)
      case t: Text          => HtmlSerialize.textContent(t.data, parentTag)
      case c: Comment       => s"<!--${c.data}-->"
      case dt: DocumentType => serializeDoctype(dt)
      case d: Document      => serializeChildren(childrenOf(d), None)
      case _                => ""

  /** Concatenate the serialization of a node's children: the `innerHTML` of an element parent. */
  private[domcore] def serializeChildren(
      children: Seq[ascent.domcore.generated.Node],
      parentTag: Option[String],
  ): String =
    val sb = StringBuilder()
    children.foreach(c => sb.append(serializeNode(c, parentTag)))
    sb.toString

  /** `<tag attrs>children</tag>`, or `<tag attrs>` for a void element (no close tag, no children). */
  private[domcore] def serializeElement(e: Element): String =
    val sb = StringBuilder()
    sb.append(openTag(e))
    if !VoidElements.isVoid(e.tagName) then
      e match
        case nb: NodeMemoryBase => sb.append(serializeChildren(nb.childList.toSeq, Some(e.tagName)))
        case _                  => ()
      sb.append("</").append(e.tagName).append('>')
    sb.toString

  /** `<!DOCTYPE name>`, or the PUBLIC / SYSTEM form when an id is present. An HTML doctype (`name = html`, both ids
    * empty) is the literal `<!DOCTYPE html>`.
    */
  private def serializeDoctype(dt: DocumentType): String =
    val name = dt.name
    val pub  = dt.publicId
    val sys  = dt.systemId
    if pub.isEmpty && sys.isEmpty then s"<!DOCTYPE $name>"
    else if sys.isEmpty then s"""<!DOCTYPE $name PUBLIC "${quoteId(pub)}">"""
    else if pub.isEmpty then s"""<!DOCTYPE $name SYSTEM "${quoteId(sys)}">"""
    else s"""<!DOCTYPE $name PUBLIC "${quoteId(pub)}" "${quoteId(sys)}">"""

  private def quoteId(id: String): String =
    id.replace("&", "&amp;").replace("\"", "&quot;")

  /** The `<tag attr="v" …>` open tag: attributes in insertion order, values escaped. Shared by the compact and pretty
    * renderers.
    */
  private def openTag(e: Element): String =
    val sb = StringBuilder()
    sb.append('<').append(e.tagName)
    e match
      case nb: NodeMemoryBase =>
        nb.attributeMap.entries.foreach { (name, value) =>
          sb.append(' ').append(name).append("=\"").append(HtmlSerialize.escapeAttr(value)).append('"')
        }
      case _ => ()
    sb.append('>')
    sb.toString
  end openTag

  private def childrenOf(node: ascent.domcore.generated.Node): Seq[ascent.domcore.generated.Node] =
    node match
      case nb: NodeMemoryBase => nb.childList.toSeq
      case _                  => Nil

  /** A human-readable, INDENTED rendering of `node` (2 spaces per level, one node per line): for debugging and readable
    * test output, NOT for morph/patch (the incidental newlines/indent would show up as text nodes). The canonical
    * machine form is [[serializeElement]] / `outerHTML` (compact). An element with a SINGLE text child is kept inline
    * (`<span>hi</span>`) since that reads better and is unambiguous; otherwise children indent onto their own lines.
    * Void elements and comments render on one line. Text uses the same raw-text rule as compact. A `Document` renders
    * its doctype, then its root element.
    */
  private[domcore] def pretty(
      node: ascent.domcore.generated.Node,
      indent: Int = 0,
      parentTag: Option[String] = None,
  ): String =
    val pad = "  " * indent
    node match
      case t: Text          => pad + HtmlSerialize.textContent(t.data, parentTag)
      case c: Comment       => pad + s"<!--${c.data}-->"
      case dt: DocumentType => pad + serializeDoctype(dt)
      case d: Document      =>
        childrenOf(d).map(k => pretty(k, indent, None)).filter(_.nonEmpty).mkString("\n")
      case e: Element =>
        val tag  = e.tagName
        val open = pad + openTag(e)
        if VoidElements.isVoid(tag) then open
        else
          val kids = childrenOf(e)
          kids match
            case Nil               => s"$open</$tag>"
            case Seq(single: Text) => s"$open${HtmlSerialize.textContent(single.data, Some(tag))}</$tag>"
            case _                 =>
              val body = kids.map(k => pretty(k, indent + 1, Some(tag))).mkString("\n")
              s"$open\n$body\n$pad</$tag>"
      case _ => ""
    end match
  end pretty
end ElementSerializationOverrides

/** Public serialization entry points over the in-memory DOM. `compact` is the canonical machine form (identical to
  * `Element.outerHTML`: the form SSR/morph consume); `pretty` is an indented, human-readable rendering for debugging
  * and readable test output. Both are pure functions of the current tree. A `Document` includes its doctype when one is
  * present. Pretty output is not a morph form: the newlines would parse back as text nodes.
  */
object Serialize:
  /** Compact markup of `node` (no incidental whitespace): the canonical machine form. */
  def compact(node: ascent.domcore.generated.Node): String = ElementSerializationOverrides.serializeNode(node)

  /** Indented, one-node-per-line rendering for humans (debugging, test diffs). NOT for morph/patch: the newlines would
    * parse back as text nodes.
    */
  def pretty(node: ascent.domcore.generated.Node): String = ElementSerializationOverrides.pretty(node, 0)
end Serialize
