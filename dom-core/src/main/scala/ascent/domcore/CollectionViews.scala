package ascent.domcore

import ascent.domcore.generated.{Attr, Element, Node, NodeList, HTMLCollection, NamedNodeMap, DOMTokenList}

/** [[NodeList]]/[[HTMLCollection]]/[[NamedNodeMap]]/[[DOMTokenList]] are LIVE VIEWS over another object's existing data
  * (a node's children, an element's attribute map, an element's class-token list) — not standalone creatable things —
  * so they take a constructor parameter no generated no-arg `class XMemory` can express (see `Renderer.memoryImpls`'s
  * `noMemoryImplClass` exclusion in `Generator.scala`; their TRAITS still generate normally into `Elements.scala`, only
  * the CLASS generation is skipped for these four).
  *
  * Each view reads `snapshot()` fresh on every call rather than caching — "live" per spec means index/length reflect
  * the CURRENT backing state, so a snapshot taken once at construction would silently go stale the moment the tree
  * mutates.
  */
final class NodeListView(snapshot: () => Seq[Node]) extends NodeList:
  def length: Int            = snapshot().length
  def item(index: Int): Node = snapshot().lift(index).getOrElse(null)

final class HTMLCollectionView(snapshot: () => Seq[Element]) extends HTMLCollection:
  def length: Int                      = snapshot().length
  def item(index: Int): Element        = snapshot().lift(index).getOrElse(null)
  def namedItem(name: String): Element =
    // `getAttribute` returns a raw String or null — wrap in Option and compare for EQUALITY (an earlier
    // version used the bare String, so `.contains(name)` was Java substring-match AND NPE'd on a null id).
    // Per spec: the first element whose `id` equals `name`, or whose `name` attribute equals `name`.
    snapshot()
      .find(e => Option(e.getAttribute("id")).contains(name) || Option(e.getAttribute("name")).contains(name))
      .getOrElse(null)
end HTMLCollectionView

/** Backed directly by an [[AttributeMap]]'s class-token attribute (`"class"`), read/written as a space-separated token
  * list — the SAME storage `Element.getAttribute("class")`/`setAttribute("class", ...)` read/write, so
  * `classList.add(...)` and `setAttribute("class", ...)` observe each other's writes, per spec.
  */
final class DOMTokenListView(attributes: AttributeMap, attrName: String = "class") extends DOMTokenList:
  private def tokens: Seq[String] =
    attributes.get(attrName).map(_.trim.split("\\s+").filter(_.nonEmpty).toSeq).getOrElse(Nil)
  private def writeTokens(ts: Seq[String]): Unit =
    if ts.isEmpty then attributes.remove(attrName) else attributes.setRaw(attrName, ts.mkString(" "))

  def length: Int              = tokens.length
  def value: String            = attributes.get(attrName).getOrElse("")
  def value_=(v: String): Unit = if v.isEmpty then attributes.remove(attrName) else attributes.setRaw(attrName, v)
  def item(index: Int): String = tokens.lift(index).getOrElse(null)
  def contains(token: String): Boolean = tokens.contains(token)

  def add(newTokens: String): Unit =
    val toAdd = newTokens.trim.split("\\s+").filter(_.nonEmpty)
    writeTokens((tokens ++ toAdd).distinct)

  def remove(removeTokens: String): Unit =
    val toRemove = removeTokens.trim.split("\\s+").filter(_.nonEmpty).toSet
    writeTokens(tokens.filterNot(toRemove.contains))

  def toggle(token: String, force: Boolean): Boolean =
    if force then
      add(token); true
    else if contains(token) then
      remove(token); false
    else
      add(token); true

  def replace(token: String, newToken: String): Boolean =
    if !contains(token) then false
    else
      writeTokens(tokens.map(t => if t == token then newToken else t).distinct)
      true

  /** No supported-token registry exists for arbitrary attributes off a real browser (`supports()` is only meaningful
    * for a handful of spec-defined enumerated attributes like `rel`/`sandbox`) — always `true` is the honest answer for
    * a generic in-memory backend with no such registry, not a fabricated allow-list.
    */
  def supports(token: String): Boolean = true
end DOMTokenListView

/** Backed by an [[AttributeMap]] — presents each stored (name, value) pair as a synthetic, ownerless [[Attr]]. `Attr`
  * nodes constructed this way are throwaway views (matching how [[Element.getAttribute]] et al. never persist an `Attr`
  * instance either) — `ownerElement` on a synthesized `Attr` isn't wired since nothing in ascent's own usage constructs
  * one that needs it; a real spec-faithful `ownerElement` link is a small, isolated follow-up if a caller ever needs
  * one.
  */
final class NamedNodeMapView(attributes: AttributeMap) extends NamedNodeMap:
  // A synthesized Attr carries its name (stamped into nodeNameRef, read back by AttrOverrides.name) and
  // value; its `ownerElement` link stays unwired (see class scaladoc — nothing in ascent needs it). Stamping
  // the name is what makes a getNamedItem round-trip report the right attribute.
  private def synthesize(name: String, value: String): Attr =
    val a = ascent.domcore.generated.AttrMemory()
    a.nodeNameRef = name
    a.value = value
    a
  private def entries: Seq[(String, String)] = attributes.entries

  def length: Int                               = entries.length
  def item(index: Int): Attr                    = entries.lift(index).map((n, v) => synthesize(n, v)).getOrElse(null)
  def getNamedItem(qualifiedName: String): Attr =
    attributes.get(qualifiedName).map(v => synthesize(qualifiedName, v)).getOrElse(null)
  def getNamedItemNS(namespace: String, localName: String): Attr = getNamedItem(localName)
  def setNamedItem(attr: Attr): Attr                             =
    val previous = getNamedItem(attr.name)
    attributes.setRaw(attr.name, attr.value)
    previous
  def setNamedItemNS(attr: Attr): Attr             = setNamedItem(attr)
  def removeNamedItem(qualifiedName: String): Attr =
    val previous = getNamedItem(qualifiedName)
    attributes.remove(qualifiedName)
    previous
  def removeNamedItemNS(namespace: String, localName: String): Attr = removeNamedItem(localName)
end NamedNodeMapView
