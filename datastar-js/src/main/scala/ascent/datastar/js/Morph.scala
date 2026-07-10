package ascent.datastar.js

import ascent.dom

import scala.scalajs.js

/** An idiomorph-equivalent DOM morph: reconcile an existing element/child-list toward new HTML by REUSING matching
  * nodes (updating their attributes/text in place) instead of replacing wholesale, so focus, caret position, scroll,
  * and in-flight animations survive an `outer`/`inner` patch — the property datastar users expect from morphing.
  *
  * Matching strategy (a pragmatic subset of idiomorph):
  *   - elements match by `id` when both carry one (an id-keyed node is found even if it moved);
  *   - otherwise by same `nodeName` at the same position;
  *   - text nodes match by position and only their `data` is rewritten (never replaced) so a caret in an adjacent
  *     editable survives;
  *   - non-matching old nodes are removed, surplus new nodes are appended/inserted.
  *
  * Works through `js.Dynamic` for the low-level node ops (the same pragmatic escape hatch `Mount` uses for
  * `classList`/`.data`), keeping the typed facade for the structural calls.
  */
object Morph:

  private val ElementNode = 1
  private val TextNode    = 3

  /** Morph `target`'s CHILDREN toward `newHtml` (the `inner` mode). */
  def inner(target: dom.Element, newHtml: String): Unit =
    val incoming = parseChildren(newHtml)
    morphChildren(target, incoming)

  /** Morph `target` ITSELF toward the single root element parsed from `newHtml` (the `outer` mode). If the new HTML has
    * a single element root with a matching `nodeName`, morph in place; otherwise fall back to a wholesale
    * `replaceWith`.
    */
  def outer(target: dom.Element, newHtml: String): Unit =
    val roots    = parseChildren(newHtml)
    val elements = roots.toarray.filter(n => nodeType(n) == ElementNode)
    if elements.length == 1 && sameNodeName(elements(0), target) then morphElement(target, elements(0))
    else
      // No single matching-tag root: import each node and replace the target wholesale.
      val imported = roots.toarray.map(cloneInto)
      target.asInstanceOf[js.Dynamic].replaceWith(imported.toSeq*)

  /** Parse an HTML fragment into a list of top-level nodes via a detached `<template>`. */
  private def parseChildren(html: String): NodeArray =
    val tmpl = dom.document.createElement("template").asInstanceOf[js.Dynamic]
    tmpl.innerHTML = html
    val content = tmpl.content
    val nodes   = content.childNodes
    val out     = js.Array[js.Dynamic]()
    val len     = nodes.length.asInstanceOf[Int]
    var i       = 0
    while i < len do
      out.push(nodes.item(i).asInstanceOf[js.Dynamic])
      i += 1
    NodeArray(out)
  end parseChildren

  /** Reconcile `parent`'s existing children against the `incoming` nodes, reusing matches. */
  private def morphChildren(parent: dom.Element, incoming: NodeArray): Unit =
    val p = parent.asInstanceOf[js.Dynamic]
    var i = 0
    while i < incoming.length do
      val newNode  = incoming(i)
      val existing = p.childNodes.item(i).asInstanceOf[js.Dynamic]
      if existing == null || js.isUndefined(existing) then p.appendChild(cloneInto(newNode))
      else if matches(existing, newNode) then morphNode(existing, newNode)
      else
        // Try to find a later existing child with the same id to move into place; else replace.
        idOf(newNode) match
          case Some(id) =>
            val moved = findChildById(p, id)
            if moved != null && !js.isUndefined(moved) then
              p.insertBefore(moved, existing)
              morphNode(moved, newNode)
            else p.replaceChild(cloneInto(newNode), existing)
          case None =>
            if sameNodeName(existing, newNode) then morphNode(existing, newNode)
            else p.replaceChild(cloneInto(newNode), existing)
      end if
      i += 1
    end while
    while p.childNodes.length.asInstanceOf[Int] > incoming.length do p.removeChild(p.lastChild)
  end morphChildren

  /** Morph one node toward another of compatible kind. */
  private def morphNode(existing: js.Dynamic, incoming: js.Dynamic): Unit =
    (nodeType(existing), nodeType(incoming)) match
      case (TextNode, TextNode) =>
        if existing.data.asInstanceOf[String] != incoming.data.asInstanceOf[String] then existing.data = incoming.data
      case (ElementNode, ElementNode) =>
        morphElement(existing.asInstanceOf[dom.Element], incoming)
      case _ =>
        existing.replaceWith(cloneInto(incoming))

  /** Morph an element in place: sync attributes, then recurse into children. The node identity is preserved (so
    * focus/selection on it or a descendant survives).
    */
  private def morphElement(existing: dom.Element, incoming: js.Dynamic): Unit =
    syncAttributes(existing.asInstanceOf[js.Dynamic], incoming)
    val incomingChildren = childNodesOf(incoming)
    morphChildren(existing, incomingChildren)

  /** Copy/normalise attributes from `incoming` onto `existing`: set/overwrite incoming attrs, remove attrs no longer
    * present. Leaves `value` on a focused input alone so typing isn't clobbered.
    */
  private def syncAttributes(existing: js.Dynamic, incoming: js.Dynamic): Unit =
    val incAttrs = incoming.attributes
    val incLen   = incAttrs.length.asInstanceOf[Int]
    val focused  = dom.document.activeElement.asInstanceOf[js.Any] eq existing.asInstanceOf[js.Any]
    var i        = 0
    while i < incLen do
      val a    = incAttrs.item(i)
      val name = a.name.asInstanceOf[String]
      val v    = a.value.asInstanceOf[String]
      if !(focused && name == "value") then
        if existing.getAttribute(name).asInstanceOf[String] != v then existing.setAttribute(name, v)
      i += 1
    // Remove attributes the incoming node no longer has.
    val exAttrs  = existing.attributes
    val toRemove = js.Array[String]()
    var j        = 0
    while j < exAttrs.length.asInstanceOf[Int] do
      val name = exAttrs.item(j).name.asInstanceOf[String]
      if !hasAttr(incoming, name) then toRemove.push(name)
      j += 1
    toRemove.foreach(n => existing.removeAttribute(n))
  end syncAttributes

  // --- small helpers over the dynamic node surface ---

  private def nodeType(n: js.Dynamic): Int = n.nodeType.asInstanceOf[Int]

  private def sameNodeName(a: js.Dynamic, b: dom.Element): Boolean =
    a.nodeName.asInstanceOf[String] == b.asInstanceOf[js.Dynamic].nodeName.asInstanceOf[String]
  private def sameNodeName(a: js.Dynamic, b: js.Dynamic): Boolean =
    a.nodeName.asInstanceOf[String] == b.nodeName.asInstanceOf[String]

  private def idOf(n: js.Dynamic): Option[String] =
    if nodeType(n) != ElementNode then None
    else
      val id = n.getAttribute("id").asInstanceOf[String]
      if id == null || id.isEmpty then None else Some(id)

  private def hasAttr(n: js.Dynamic, name: String): Boolean =
    n.hasAttribute(name).asInstanceOf[Boolean]

  /** Match: same id (if both have one), else same node type + name. */
  private def matches(existing: js.Dynamic, incoming: js.Dynamic): Boolean =
    (idOf(existing), idOf(incoming)) match
      case (Some(a), Some(b)) => a == b
      case (Some(_), None)    => false
      case (None, Some(_))    => false
      case (None, None)       => nodeType(existing) == nodeType(incoming) && sameNodeName(existing, incoming)

  private def findChildById(parent: js.Dynamic, id: String): js.Dynamic =
    parent.querySelector(s"#$id").asInstanceOf[js.Dynamic]

  private def childNodesOf(n: js.Dynamic): NodeArray =
    val nodes = n.childNodes
    val out   = js.Array[js.Dynamic]()
    val len   = nodes.length.asInstanceOf[Int]
    var i     = 0
    while i < len do
      out.push(nodes.item(i).asInstanceOf[js.Dynamic])
      i += 1
    NodeArray(out)

  /** Import a parsed node into the live document before insertion (template content is inert). */
  private def cloneInto(n: js.Dynamic): js.Dynamic =
    dom.document.asInstanceOf[js.Dynamic].importNode(n, true).asInstanceOf[js.Dynamic]

  /** Thin wrapper so we can index/length a captured snapshot of nodes without re-reading a live list. */
  private final class NodeArray(arr: js.Array[js.Dynamic]):
    def length: Int                   = arr.length
    def apply(i: Int): js.Dynamic     = arr(i)
    def toarray: js.Array[js.Dynamic] = arr
end Morph
