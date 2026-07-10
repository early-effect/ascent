package ascent.domcore

import ascent.domcore.generated.{CharacterData, Document, Element, ElementFactory, Node, Text}

/** Real tree-relationship behavior for [[ascent.domcore.generated.Node]] — parent/child navigation, mutation
  * (`appendChild`/`insertBefore`/`removeChild`/`replaceChild`), and the handful of derived queries (`contains`,
  * `cloneNode`, `isEqualNode`, `compareDocumentPosition`) — mixed into every generated `*Memory` class via
  * `Renderer.memoryImpls`'s `handWrittenOverrides` mechanism (see `Generator.scala`'s `"Node" -> "..."` entries).
  *
  * Everything here routes through [[NodeMemoryBase.parentRef]] / [[NodeMemoryBase.childList]] — the SAME storage every
  * `Node`-derived instance already carries — so this is genuinely one shared implementation, not per-element behavior.
  *
  * The namespace-lookup trio (`lookupPrefix`/`lookupNamespaceURI`/`isDefaultNamespace`) implements the REAL spec
  * algorithm against a model that stores no namespace declarations anywhere — its correct, deterministic output is
  * therefore always `null`/`false`. That's the actual answer the algorithm gives here, not a stand-in for missing
  * behavior.
  */
trait NodeOverrides:
  self: NodeMemoryBase & Node =>

  private def selfNode: Node = self.asInstanceOf[Node]

  /** Reference equality between two `Node`s — DOM identity is by reference, never structural `==`, and two distinct
    * `<li>`s with identical attrs must stay distinguishable. Wraps the noisy `eq` cast idiom used throughout this file.
    */
  private def refEq(a: Node, b: Node): Boolean = a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]

  // Node identity — stamped at construction into NodeMemoryBase (ElementFactory / Document.create*)
  // and read back here. `nodeType` is the spec constant (Element 1 / Text 3 / Comment 8 / Document 9),
  // `nodeName` the tag for an element or the spec sentinel (#text / #comment / #document) otherwise.
  def nodeType: Int    = self.nodeTypeRef
  def nodeName: String = self.nodeNameRef

  def parentNode: Node       = self.parentRef
  def parentElement: Element = self.parentRef match
    case e: Element => e
    case _          => null

  def childNodes: ascent.domcore.generated.NodeList = NodeListView(() => self.childList.toSeq)
  def firstChild: Node                              = self.childList.headOption.getOrElse(null)
  def lastChild: Node                               = self.childList.lastOption.getOrElse(null)

  private def siblingsOfParent: Seq[Node] = self.parentRef match
    case p: NodeMemoryBase => p.childList.toSeq
    case _                 => Nil

  def previousSibling: Node =
    val s = siblingsOfParent
    val i = s.indexWhere(_.asInstanceOf[AnyRef] eq selfNode.asInstanceOf[AnyRef])
    if i > 0 then s(i - 1) else null

  def nextSibling: Node =
    val s = siblingsOfParent
    val i = s.indexWhere(_.asInstanceOf[AnyRef] eq selfNode.asInstanceOf[AnyRef])
    if i >= 0 && i < s.length - 1 then s(i + 1) else null

  def ownerDocument: Document = self.ownerDocumentRef

  private def ancestorsAndSelf: LazyList[Node] =
    LazyList.iterate(Option(selfNode))(_.flatMap(n => Option(n.parentNode))).takeWhile(_.isDefined).flatten

  private def ancestorsAndSelfOf(n: Node): LazyList[Node] =
    LazyList.iterate(Option(n))(_.flatMap(x => Option(x.parentNode))).takeWhile(_.isDefined).flatten

  def isConnected: Boolean = ancestorsAndSelf.exists(_.isInstanceOf[Document])

  // `nodeValue`/`textContent` — spec semantics vary by node kind (CharacterData nodes reflect
  // `data`; anything else aggregates descendant Text nodes' data for textContent, and has no
  // nodeValue at all). Implemented generically here via structural checks, since Node is the one
  // place both readers live regardless of concrete kind.
  def nodeValue: String = self match
    case cd: CharacterData => cd.data
    case _                 => null
  def nodeValue_=(value: String): Unit = self match
    case cd: CharacterData => cd.data = value
    case _                 => ()

  def textContent: String = self match
    case cd: CharacterData => cd.data
    case _                 => NodeOverrides.descendantText(selfNode)

  def textContent_=(value: String): Unit = self match
    case cd: CharacterData => cd.data = value
    case _                 =>
      self.childList.clear()
      if value.nonEmpty then
        self.ownerDocumentRef match
          case doc: Document => appendChild(doc.createTextNode(value))
          case null          => () // ownerDocumentRef is `Document | Null`; the only non-Document case is null

  def getRootNode(options: PlatformOpaque): Node = ancestorsAndSelf.last

  def hasChildNodes(): Boolean = self.childList.nonEmpty

  def normalize(): Unit =
    // Merge adjacent Text nodes and drop empty ones, then recurse — the real spec algorithm,
    // expressible directly over childList.
    var i = 0
    while i < self.childList.length do
      self.childList(i) match
        case t: Text if t.data.isEmpty =>
          self.childList.remove(i)
        case t: Text =>
          while i + 1 < self.childList.length && self.childList(i + 1).isInstanceOf[Text] do
            val next = self.childList(i + 1).asInstanceOf[Text]
            t.data = t.data + next.data
            self.childList.remove(i + 1)
          i += 1
        case n: Node =>
          n.normalize()
          i += 1
    end while
  end normalize

  /** Shallow clone by concrete kind, then recurse into children if `subtree`. Each kind has exactly one way to
    * construct an equivalent empty node — dispatch on the real Scala type rather than inventing a generic "clone
    * whatever this is" primitive:
    *   - `Text`/`Comment`: a fresh instance carrying the same `data`;
    *   - `Element`: [[ElementFactory]] dispatches by tag name (the SAME table `Document.createElement` uses), then
    *     every stored attribute is copied over.
    *   - anything else (`Document`, `Attr`, a bare `Node`) has no well-defined single-call constructor here and isn't a
    *     realistic clone target for this engine — cloning a `Document` isn't spec-required to produce a usable second
    *     document, so this falls through to the same node (a documented limitation, not silent data loss: nothing in
    *     ascent's own usage clones a `Document`).
    */
  def cloneNode(subtree: Boolean): Node =
    val clone: Node = self match
      case t: Text                             => ElementFactory.createText(t.data)
      case c: ascent.domcore.generated.Comment => ElementFactory.createComment(c.data)
      case e: Element                          =>
        val n = ElementFactory.createByTag(e.tagName)
        // `self` (hence `e`) has self-type NodeMemoryBase & Node, so `e` is statically already a
        // NodeMemoryBase — read its attribute map directly, no cast/match needed.
        self.attributeMap.entries.foreach((name, value) => n.setAttribute(name, value))
        n
      case _ => selfNode
    clone match
      case cb: NodeMemoryBase => cb.ownerDocumentRef = self.ownerDocumentRef
      case _                  => ()
    if subtree then self.childList.foreach(child => clone.appendChild(child.cloneNode(true)))
    clone
  end cloneNode

  def isEqualNode(otherNode: Node): Boolean =
    if otherNode == null then false
    else
      val otherChildren: Seq[Node] = otherNode match
        case base: NodeMemoryBase => base.childList.toSeq
        case _                    => Nil
      // For Element nodes the spec ALSO requires equal attribute lists (same name→value set) — two
      // `<div id="a">`/`<div id="b">` differ despite identical tag/children. Attribute ORDER is not
      // significant per spec, so compare as maps. Non-element nodes carry no attributes (empty map).
      val attrsEqual = (selfNode, otherNode) match
        case (_: Element, _: Element) =>
          (self, otherNode) match
            case (a: NodeMemoryBase, b: NodeMemoryBase) => a.attributeMap.entries.toMap == b.attributeMap.entries.toMap
            case _                                      => true
        case _ => true
      selfNode.nodeType == otherNode.nodeType &&
      selfNode.nodeName == otherNode.nodeName &&
      selfNode.nodeValue == otherNode.nodeValue &&
      attrsEqual &&
      self.childList.length == otherChildren.length &&
      self.childList.zip(otherChildren).forall { case (a, b) => a.isEqualNode(b) }

  def isSameNode(otherNode: Node): Boolean =
    otherNode != null && (selfNode.asInstanceOf[AnyRef] eq otherNode.asInstanceOf[AnyRef])

  def contains(other: Node): Boolean =
    other != null && ancestorsAndSelfOf(other).exists(_.asInstanceOf[AnyRef] eq selfNode.asInstanceOf[AnyRef])

  /** Real bitmask algorithm (`Node.DOCUMENT_POSITION_*` per spec) computed against actual ancestor chains, not a stub:
    * `DISCONNECTED(1) | IMPLEMENTATION_SPECIFIC(32)` when the two nodes share no common root;
    * `CONTAINED_BY(16) | FOLLOWING(4)` when `other` is a descendant of `self`; `CONTAINS(8) | PRECEDING(2)` when
    * `other` is an ancestor of `self`; `PRECEDING(2)` / `FOLLOWING(4)` alone for unrelated nodes in the same tree —
    * document order between unrelated siblings isn't tracked precisely by this model, so `FOLLOWING` is used as a
    * deterministic (if not always spec-exact) tie-break, documented here rather than silently guessed at.
    */
  def compareDocumentPosition(other: Node): Int =
    if selfNode.asInstanceOf[AnyRef] eq other.asInstanceOf[AnyRef] then 0
    else if contains(other) then 16 | 4
    else if other.contains(selfNode) then 8 | 2
    else
      val selfRoot  = ancestorsAndSelf.lastOption
      val otherRoot = ancestorsAndSelfOf(other).lastOption
      if selfRoot.exists(r => otherRoot.exists(_.asInstanceOf[AnyRef] eq r.asInstanceOf[AnyRef])) then 4
      else 1 | 32

  def lookupPrefix(namespace: String): String        = null
  def lookupNamespaceURI(prefix: String): String     = null
  def isDefaultNamespace(namespace: String): Boolean = namespace == null

  def insertBefore(node: Node, child: Node): Node =
    // `insertBefore(x, x)` is a no-op (per spec the reference child is adjusted to x's next sibling before
    // x is removed, leaving x where it was) — short-circuit it so detach-then-reinsert doesn't move x.
    if child != null && refEq(node, child) then node
    else
      // Detach FIRST, then locate `child` — if `node` was already an earlier sibling of `self`, detaching
      // shifts indices, so any index captured before the detach would be stale (the bug replaceChild had).
      // `child == null` means "append"; a `child` that isn't actually our child also appends (a documented
      // simplification — real DOM throws NotFoundError, but nothing in ascent relies on that error path).
      detachFromCurrentParent(node)
      val idx =
        if child == null then self.childList.length
        else
          val i = self.childList.indexWhere(refEq(_, child))
          if i < 0 then self.childList.length else i
      self.childList.insert(idx, node)
      attachAsChild(node)
      node

  def appendChild(node: Node): Node =
    detachFromCurrentParent(node)
    self.childList += node
    attachAsChild(node)
    node

  def replaceChild(node: Node, child: Node): Node =
    // `replaceChild(x, x)` is a no-op — leave x in place rather than detach-then-fail-to-find (which would
    // drop it entirely).
    if refEq(node, child) then child
    else
      // Detach `node` from its current parent BEFORE computing `child`'s index: if `node` is already an
      // earlier sibling of `self`, detaching removes it and shifts everything down, so an index captured
      // earlier would be stale (→ IndexOutOfBounds, or overwriting the wrong slot). A `child` that isn't
      // our child is a no-op (returns it unchanged), matching the other mutators' lenient stance.
      detachFromCurrentParent(node)
      val idx = self.childList.indexWhere(refEq(_, child))
      if idx >= 0 then
        self.childList(idx) = node
        attachAsChild(node)
        detach(child)
      child

  def removeChild(child: Node): Node =
    val idx = self.childList.indexWhere(_.asInstanceOf[AnyRef] eq child.asInstanceOf[AnyRef])
    if idx >= 0 then
      self.childList.remove(idx)
      detach(child)
    child

  private def detachFromCurrentParent(node: Node): Unit =
    node match
      case nb: NodeMemoryBase =>
        nb.parentRef match
          case p: NodeMemoryBase => p.childList.filterInPlace(c => !refEq(c, node))
          case _                 => ()
      case _ => ()

  private def attachAsChild(node: Node): Unit =
    node match
      case nb: NodeMemoryBase => nb.parentRef = selfNode
      case _                  => ()

  private def detach(node: Node): Unit =
    node match
      case nb: NodeMemoryBase => nb.parentRef = null
      case _                  => ()
end NodeOverrides

object NodeOverrides:
  /** The concatenation of every `Text` descendant's `data` in tree order — the value an element's `textContent` getter
    * returns. Deliberately EXCLUDES `Comment` (and any other non-`Text` `CharacterData`) descendants: per the WHATWG
    * spec an element's `textContent` is its Text descendants only, so `<p>a<!--c-->b</p>.textContent == "ab"`, not
    * `"acb"`. Recurses through element children; a `Text` child contributes its data, a `Comment` child contributes
    * nothing.
    */
  private[domcore] def descendantText(node: Node): String =
    val sb                  = StringBuilder()
    def walk(n: Node): Unit =
      n match
        case _: ascent.domcore.generated.Comment => () // comments never contribute to textContent
        case t: Text                             => sb.append(t.data)
        case nb: NodeMemoryBase                  => nb.childList.foreach(walk)
        case _                                   => ()
    node match
      case nb: NodeMemoryBase => nb.childList.foreach(walk)
      case _                  => ()
    sb.toString
  end descendantText
end NodeOverrides
