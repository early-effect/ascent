package ascent.domcore

import ascent.css.{Matchable, Sel}
import ascent.domcore.generated.{DOMTokenList, Element, HTMLCollection, NamedNodeMap, Node}

// `Matchable[Element]` lives in `ElementOverrides`'s own companion object below — NOT automatically found by
// implicit search for `sel.matches(e)` calls inside this trait's own body (search looks at the companions of
// `Matchable`/`Element`, not of the enclosing trait), so it needs an explicit import here.
import ElementOverrides.given

/** Real attribute/tree-query behavior for [[ascent.domcore.generated.Element]] — all routed through the SAME
  * [[NodeMemoryBase.attributeMap]] / [[NodeMemoryBase.childList]] every reflected-attribute accessor and `Node`'s tree
  * methods already use, plus the `querySelector` family delegated to `css`'s real selector engine via a [[Matchable]]
  * instance for this exact type.
  *
  * `setAttribute`/`setAttributeNS`'s real WebIDL value type is `TrustedType or DOMString` (see
  * `ascent.domgen.DefBuilder.structuralType`'s union-splitting) — a plain `String` argument satisfies the
  * `PlatformOpaque | String` union directly, no wrapper method needed.
  */
// `ElementOverrides` is the single trait the generator mixes into `ElementMemory` (one `<Interface>Overrides` per
// interface). It gathers the hand-written Element behavior; the innerHTML/outerHTML serialization lives in its own
// file for focus and is folded in via this `extends`, so `ElementMemory` picks it up through the one mixin.
trait ElementOverrides extends ElementSerializationOverrides:
  self: NodeMemoryBase & Element =>

  def hasAttributes(): Boolean = !self.attributeMap.isEmpty

  def getAttributeNames(): List[String] = self.attributeMap.names.toList

  def getAttribute(qualifiedName: String): String = self.attributeMap.get(qualifiedName).getOrElse(null)

  def getAttributeNS(namespace: String, localName: String): String = getAttribute(localName)

  def setAttribute(qualifiedName: String, value: PlatformOpaque | String): Unit =
    value match
      case s: String => self.attributeMap.setRaw(qualifiedName, s)
      case _         => ()

  def setAttributeNS(namespace: String, qualifiedName: String, value: PlatformOpaque | String): Unit =
    setAttribute(qualifiedName, value)

  def removeAttribute(qualifiedName: String): Unit = self.attributeMap.remove(qualifiedName)

  def removeAttributeNS(namespace: String, localName: String): Unit = removeAttribute(localName)

  def hasAttribute(qualifiedName: String): Boolean = self.attributeMap.contains(qualifiedName)

  def hasAttributeNS(namespace: String, localName: String): Boolean = hasAttribute(localName)

  def toggleAttribute(qualifiedName: String, force: Boolean): Boolean =
    if force then
      self.attributeMap.setRaw(qualifiedName, ""); true
    else if hasAttribute(qualifiedName) then
      removeAttribute(qualifiedName); false
    else
      self.attributeMap.setRaw(qualifiedName, ""); true

  def attributes: NamedNodeMap = NamedNodeMapView(self.attributeMap)

  def children: HTMLCollection = HTMLCollectionView(() => self.childList.collect { case e: Element => e }.toSeq)

  def classList: DOMTokenList = DOMTokenListView(self.attributeMap)

  def tagName: String = self.nodeName

  // id/className reflect their content attributes (`id` / `class`) directly — the DOM spec marks both
  // [Reflect], though the vendored webref snapshot omits that tag (see Generator's handWrittenOverrides
  // note). Routed through the SAME attribute map as getAttribute/setAttribute, so `el.id = "x"` and
  // `el.setAttribute("id", "x")` are the same write, and getElementById / `#id` selectors see it.
  def id: String                       = self.attributeMap.get("id").getOrElse("")
  def id_=(value: String): Unit        = self.attributeMap.setRaw("id", value)
  def className: String                = self.attributeMap.get("class").getOrElse("")
  def className_=(value: String): Unit = self.attributeMap.setRaw("class", value)

  def querySelector(selectors: String): Element =
    Sel.parse(selectors) match
      case Left(_)    => null
      case Right(sel) => ElementOverrides.descendantsOf(self).find(e => sel.matches(e)).getOrElse(null)

  def querySelectorAll(selectors: String): ascent.domcore.generated.NodeList =
    val matched: Seq[Node] = Sel.parse(selectors) match
      case Left(_)    => Nil
      case Right(sel) => ElementOverrides.descendantsOf(self).filter(e => sel.matches(e))
    NodeListView(() => matched)

  def matches(selectors: String): Boolean =
    Sel.parse(selectors) match
      case Left(_) => false
      // `sel.matches[E]` infers E from the argument's static type; self's type here is the
      // NodeMemoryBase & Element & ElementOverrides intersection, not bare Element, so it must
      // be widened explicitly for the Matchable[Element] given to apply.
      case Right(sel) => sel.matches(self: Element)

  def closest(selectors: String): Element =
    Sel.parse(selectors) match
      case Left(_)    => null
      case Right(sel) => ElementOverrides.selfAndAncestorsOf(self).find(e => sel.matches(e)).getOrElse(null)

  def getElementsByTagName(qualifiedName: String): HTMLCollection =
    // `"*"` matches every descendant element; otherwise match the tag ASCII-case-insensitively (HTML tag
    // names are case-insensitive on the wire — `getElementsByTagName("DIV")` finds `<div>`). Our stored
    // tagName is already lowercased at construction, so lowercase the query to compare.
    val wanted = qualifiedName.toLowerCase
    HTMLCollectionView(() => ElementOverrides.descendantsOf(self).filter(e => wanted == "*" || e.tagName == wanted))

  def getElementsByClassName(classNames: String): HTMLCollection =
    val wanted = classNames.trim.split("\\s+").filter(_.nonEmpty).toSet
    // An empty/whitespace-only class string matches NOTHING (spec: empty collection). Without this guard
    // `wanted` is empty and `forall` on it is vacuously true → every descendant would match.
    if wanted.isEmpty then HTMLCollectionView(() => Nil)
    else HTMLCollectionView(() => ElementOverrides.descendantsOf(self).filter(e => wanted.forall(e.classList.contains)))
end ElementOverrides

object ElementOverrides:
  /** Materializes an [[HTMLCollection]] view into a plain `Seq[Element]` — the view's own surface (`length`/`item`) is
    * what the WebIDL contract requires callers see, but internal tree walks here want ordinary Scala collection
    * operations.
    */
  private def elementsOf(hc: HTMLCollection): Seq[Element] = (0 until hc.length).map(hc.item)

  /** Every descendant Element, in document order — the search space `querySelector`/`querySelectorAll`/
    * `getElementsByTagName`/`getElementsByClassName` all walk (the element's own subtree, self excluded).
    */
  private def descendantsOf(e: Element): Seq[Element] =
    elementsOf(e.children).flatMap(c => c +: descendantsOf(c))

  private def selfAndAncestorsOf(e: Element): LazyList[Element] =
    LazyList.iterate(Option(e))(_.flatMap(x => Option(x.parentElement))).takeWhile(_.isDefined).flatten

  /** [[Matchable]] instance for the in-memory/JS-adapter `Element` type, wiring `css`'s real selector engine into
    * `querySelector`/`matches`/`closest` above. `attr("class")`/`attr("id")` deliberately route through `getAttribute`
    * (not [[classes]]/[[id]] directly) since CSS attribute selectors (`[class~="foo"]`) query the raw attribute string,
    * matching spec semantics distinct from the `.foo`/`#foo` shorthand paths.
    */
  given Matchable[Element] with
    def tagName(e: Element): String                    = e.tagName
    def attr(e: Element, name: String): Option[String] = Option(e.getAttribute(name))
    def classes(e: Element): Set[String]               =
      Option(e.getAttribute("class")).toSet.flatMap(_.trim.split("\\s+").filter(_.nonEmpty).toSet)
    def id(e: Element): Option[String]             = Option(e.getAttribute("id"))
    def parent(e: Element): Option[Element]        = Option(e.parentElement)
    def children(e: Element): Seq[Element]         = elementsOf(e.children)
    def previousSiblings(e: Element): Seq[Element] =
      parent(e).map(p => children(p).takeWhile(_ ne e)).getOrElse(Nil)
    def nextSiblings(e: Element): Seq[Element] =
      parent(e).map(p => children(p).dropWhile(_ ne e).drop(1)).getOrElse(Nil)
  end given
end ElementOverrides
