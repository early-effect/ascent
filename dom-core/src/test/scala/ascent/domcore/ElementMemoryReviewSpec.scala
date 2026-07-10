package ascent.domcore

import ascent.domcore.generated.{Document, Element}
import zio.test.*

/** Regression tests for correctness bugs found in the dom-core kernel code review; each would fail the pre-review code
  * and cites the WHATWG DOM semantics it pins.
  */
object ElementMemoryReviewSpec extends ZIOSpecDefault:

  private def doc: Document = ascent.domcore.generated.DocumentMemory()

  private def eqRef(a: AnyRef, b: AnyRef): Boolean = a eq b

  def spec = suite("dom-core kernel — code-review regressions")(
    test("replaceChild(node, child) where node is ALREADY an earlier sibling doesn't crash or corrupt") {
      // [A, B, C].replaceChild(A, C): the captured index of C (2) went stale after A detached
      // (list became [B, C]), so childList(2) = A threw IndexOutOfBounds. Result must be [B, A].
      val d = doc
      val p = d.createElement("div", "")
      val a = d.createElement("span", "")
      val b = d.createElement("span", "")
      val c = d.createElement("span", "")
      p.appendChild(a); p.appendChild(b); p.appendChild(c)
      p.replaceChild(a, c) // move A into C's slot
      val kids = (0 until p.childNodes.length).map(p.childNodes.item)
      assertTrue(
        p.childNodes.length == 2,
        eqRef(kids(0), b),
        eqRef(kids(1), a),
      )
    },
    test("replaceChild(node, child) with node an earlier sibling than a MIDDLE child keeps both correct") {
      // [A, B, C].replaceChild(A, B): stale-index version produced [B, A] (dropping C). Correct is [A, C].
      val d = doc
      val p = d.createElement("div", "")
      val a = d.createElement("span", "")
      val b = d.createElement("span", "")
      val c = d.createElement("span", "")
      p.appendChild(a); p.appendChild(b); p.appendChild(c)
      p.replaceChild(a, b)
      val kids = (0 until p.childNodes.length).map(p.childNodes.item)
      assertTrue(kids.length == 2, eqRef(kids(0), a), eqRef(kids(1), c))
    },
    test("replaceChild(x, x) is a no-op keeping x in place") {
      val d = doc
      val p = d.createElement("div", "")
      val a = d.createElement("i", "")
      val x = d.createElement("b", "")
      p.appendChild(a); p.appendChild(x)
      p.replaceChild(x, x)
      val kids = (0 until p.childNodes.length).map(p.childNodes.item)
      assertTrue(kids.length == 2, eqRef(kids(0), a), eqRef(kids(1), x))
    },
    test("HTMLCollection.namedItem uses EXACT id match and never NPEs on elements lacking id/name") {
      // getAttribute returns a raw String|null, so `.contains(name)` was Java substring-match + NPE.
      val d     = doc
      val root  = d.createElement("div", "")
      val plain = d.createElement("span", "") // no id/name — must not NPE
      val hit   = d.createElement("span", "")
      hit.setAttribute("id", "foobar")
      root.appendChild(plain); root.appendChild(hit)
      val coll = root.children
      assertTrue(
        coll.namedItem("nope") == null,      // no NPE despite plain having no id
        coll.namedItem("foo") == null,       // substring must NOT match "foobar"
        eqRef(coll.namedItem("foobar"), hit), // exact match wins
      )
    },
    test("an element's textContent EXCLUDES comment-node data (only Text descendants count)") {
      val d = doc
      val p = d.createElement("p", "")
      p.appendChild(d.createTextNode("a"))
      p.appendChild(d.createComment("HIDDEN"))
      p.appendChild(d.createTextNode("b"))
      assertTrue(p.textContent == "ab")
    },
    test("textContent of nested elements still aggregates their Text but skips their comments") {
      val d    = doc
      val div  = d.createElement("div", "")
      val span = d.createElement("span", "")
      span.appendChild(d.createTextNode("x"))
      span.appendChild(d.createComment("c"))
      div.appendChild(d.createTextNode("["))
      div.appendChild(span)
      div.appendChild(d.createTextNode("]"))
      assertTrue(div.textContent == "[x]")
    },
    test("isEqualNode is FALSE for two elements that differ only in attributes") {
      val d = doc
      val a = d.createElement("div", "")
      a.setAttribute("id", "a")
      val b = d.createElement("div", "")
      b.setAttribute("id", "b")
      assertTrue(!a.isEqualNode(b))
    },
    test("isEqualNode is TRUE for two structurally identical elements (same tag, attrs, children)") {
      val d                = doc
      def build(): Element =
        val e = d.createElement("div", "")
        e.setAttribute("class", "x")
        e.appendChild(d.createTextNode("hi"))
        e
      assertTrue(build().isEqualNode(build()))
    },
    test("CharacterData.length reflects the data length, not a hardcoded 0") {
      val d = doc
      assertTrue(d.createTextNode("abc").length == 3, d.createTextNode("").length == 0)
    },
    test("getElementsByClassName(\"\") returns nothing, not every descendant") {
      val d    = doc
      val root = d.createElement("div", "")
      root.appendChild(d.createElement("span", ""))
      root.appendChild(d.createElement("span", ""))
      assertTrue(root.getElementsByClassName("").length == 0)
    },
    test("getElementsByTagName matches case-insensitively (HTML), and \"*\" matches all elements") {
      val d    = doc
      val root = d.createElement("ul", "")
      root.appendChild(d.createElement("li", ""))
      root.appendChild(d.createElement("li", ""))
      assertTrue(
        root.getElementsByTagName("LI").length == 2, // HTML tag match is ASCII-case-insensitive
        root.getElementsByTagName("*").length == 2,  // universal
      )
    },
    test("a synthesized Attr from getNamedItem reports its name (round-trips through NamedNodeMap)") {
      val d  = doc
      val el = d.createElement("div", "")
      el.setAttribute("data-x", "42")
      val attr = el.attributes.getNamedItem("data-x")
      assertTrue(attr != null, attr.name == "data-x", attr.value == "42")
    },
  )
end ElementMemoryReviewSpec
