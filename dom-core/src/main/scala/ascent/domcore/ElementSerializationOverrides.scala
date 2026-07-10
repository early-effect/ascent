package ascent.domcore

import ascent.domcore.generated.{Comment, Element, Text}
import ascent.domtypes.VoidElements

/** Real `innerHTML` / `outerHTML` for the in-memory [[ascent.domcore.generated.Element]] — a genuine DOM feature (a
  * live subtree serialized to a markup string), also the foundation of the SSR path (mount into an in-memory tree, then
  * read `root.innerHTML`).
  *
  * Output is COMPACT (WHATWG fragment-serialization: no incidental whitespace, no newlines) — the form a morph/patch
  * consumer diffs, where stray whitespace would show up as spurious text nodes. A separate indented rendering for
  * humans lives in [[HtmlSerialize]]-adjacent tooling ([[Element]] itself only ever produces the canonical compact
  * form, matching a browser's `outerHTML`).
  *
  *   - attributes emit in the element's stored (insertion) order — `attributeMap` is a `LinkedHashMap`;
  *   - a boolean/presence attribute stored as `""` emits `name=""` (the canonical HTML form);
  *   - void elements (`br`, `input`, …) emit no close tag and no children;
  *   - `Text` nodes escape `& < >`; `Comment` nodes emit `<!--data-->` (data unescaped, per spec — a comment can't
  *     contain `-->`, which the in-memory model never produces);
  *   - attribute values escape via [[HtmlSerialize.escapeAttr]].
  *
  * The setters (`innerHTML_=` / `outerHTML_=`) parse markup back into a tree, which this in-memory model doesn't do, so
  * they stay `???` — an honest gap (nothing in ascent's own usage sets innerHTML on the in-memory backend; the browser
  * backend has the real thing).
  */
trait ElementSerializationOverrides:
  self: NodeMemoryBase & Element =>

  def innerHTML: PlatformOpaque | String = ElementSerializationOverrides.serializeChildren(self.childList.toSeq)
  def innerHTML_=(value: PlatformOpaque | String): Unit = ???

  def outerHTML: PlatformOpaque | String                = ElementSerializationOverrides.serializeElement(self)
  def outerHTML_=(value: PlatformOpaque | String): Unit = ???
end ElementSerializationOverrides

object ElementSerializationOverrides:
  /** Serialize one node (element/text/comment) to its compact markup, recursing into element children. */
  private[domcore] def serializeNode(node: ascent.domcore.generated.Node): String =
    node match
      case e: Element => serializeElement(e)
      case t: Text    => HtmlSerialize.escapeText(t.data)
      case c: Comment => s"<!--${c.data}-->"
      case _          => ""

  /** Concatenate the serialization of a node's children — the `innerHTML` of the parent. */
  private[domcore] def serializeChildren(children: Seq[ascent.domcore.generated.Node]): String =
    val sb = StringBuilder()
    children.foreach(c => sb.append(serializeNode(c)))
    sb.toString

  /** `<tag attrs>children</tag>`, or `<tag attrs>` for a void element (no close tag, no children). */
  private[domcore] def serializeElement(e: Element): String =
    val sb = StringBuilder()
    sb.append(openTag(e))
    if !VoidElements.isVoid(e.tagName) then
      e match
        case nb: NodeMemoryBase => sb.append(serializeChildren(nb.childList.toSeq))
        case _                  => ()
      sb.append("</").append(e.tagName).append('>')
    sb.toString

  /** The `<tag attr="v" …>` open tag — attributes in insertion order, values escaped. Shared by the compact and pretty
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

  /** A human-readable, INDENTED rendering of `node` (2 spaces per level, one node per line) — for debugging and
    * readable test output, NOT for morph/patch (the incidental newlines/indent would show up as text nodes). The
    * canonical machine form is [[serializeElement]] / `outerHTML` (compact). An element with a SINGLE text child is
    * kept inline (`<span>hi</span>`) since that reads better and is unambiguous; otherwise children indent onto their
    * own lines. Void elements and comments render on one line; text nodes render their escaped data.
    */
  private[domcore] def pretty(node: ascent.domcore.generated.Node, indent: Int = 0): String =
    val pad = "  " * indent
    node match
      case t: Text    => pad + HtmlSerialize.escapeText(t.data)
      case c: Comment => pad + s"<!--${c.data}-->"
      case e: Element =>
        val tag  = e.tagName
        val open = pad + openTag(e)
        if VoidElements.isVoid(tag) then open
        else
          val kids = childrenOf(e)
          kids match
            case Nil               => s"$open</$tag>"
            case Seq(single: Text) => s"$open${HtmlSerialize.escapeText(single.data)}</$tag>"
            case _                 =>
              val body = kids.map(k => pretty(k, indent + 1)).mkString("\n")
              s"$open\n$body\n$pad</$tag>"
      case _ => ""
    end match
  end pretty
end ElementSerializationOverrides

/** Public serialization entry points over the in-memory DOM. `compact` is the canonical machine form (identical to
  * `Element.outerHTML` — the form SSR/morph consume); `pretty` is an indented, human-readable rendering for debugging
  * and readable test output. Both are pure functions of the current tree.
  */
object Serialize:
  /** Compact `outerHTML` of `node` (no incidental whitespace) — the canonical machine form. */
  def compact(node: ascent.domcore.generated.Node): String = ElementSerializationOverrides.serializeNode(node)

  /** Indented, one-node-per-line rendering for humans (debugging, test diffs). NOT for morph/patch — the newlines would
    * parse back as text nodes.
    */
  def pretty(node: ascent.domcore.generated.Node): String = ElementSerializationOverrides.pretty(node, 0)
end Serialize
