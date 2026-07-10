package ascent.domcore

import ascent.domcore.generated.{Document, Element, Text}
import zio.test.*

/** The in-memory DOM backend's behavior contract, exercised against `DocumentMemory` with no platform DOM so it runs
  * identically on jvm/js/native.
  */
object ElementMemorySpec extends ZIOSpecDefault:

  private def doc: Document = ascent.domcore.generated.DocumentMemory()

  def spec = suite("dom-core in-memory backend (ElementMemory / DocumentMemory)")(
    suite("node identity")(
      test("createElement stamps the element's tagName from the tag it was created with") {
        val d = doc
        assertTrue(d.createElement("div", "").tagName == "div")
      },
      test("createElement dispatches to the right concrete interface by tag name") {
        val d = doc
        assertTrue(
          d.createElement("input", "").isInstanceOf[ascent.domcore.generated.HTMLInputElement],
          d.createElement("a", "").isInstanceOf[ascent.domcore.generated.HTMLAnchorElement],
        )
      },
      test("an unknown tag name falls back to HTMLUnknownElement but keeps its own tagName") {
        val d  = doc
        val el = d.createElement("made-up-tag", "")
        assertTrue(
          el.isInstanceOf[ascent.domcore.generated.HTMLUnknownElement],
          el.tagName == "made-up-tag",
        )
      },
      test("nodeType is spec-correct per kind: Element=1, Text=3, Comment=8, Document=9") {
        val d = doc
        assertTrue(
          d.createElement("div", "").nodeType == 1,
          d.createTextNode("hi").nodeType == 3,
          d.createComment("x").nodeType == 8,
          d.nodeType == 9,
        )
      },
      test("nodeName is the tag for elements, and the spec sentinel for text/comment/document") {
        val d = doc
        assertTrue(
          d.createElement("section", "").nodeName == "section",
          d.createTextNode("hi").nodeName == "#text",
          d.createComment("x").nodeName == "#comment",
          d.nodeName == "#document",
        )
      },
    ),
    suite("attributes")(
      test("a reflected attribute round-trips through the attribute map (id)") {
        val d  = doc
        val el = d.createElement("div", "")
        el.id = "main"
        assertTrue(el.id == "main", el.getAttribute("id") == "main")
      },
      test("setAttribute and the reflected property observe each other's writes (same storage)") {
        val d  = doc
        val el = d.createElement("div", "")
        el.setAttribute("id", "x")
        assertTrue(el.id == "x")
        el.id = "y"
        assertTrue(el.getAttribute("id") == "y")
      },
      test("getAttribute returns null for an absent attribute; hasAttribute reflects presence") {
        val d  = doc
        val el = d.createElement("div", "")
        assertTrue(el.getAttribute("nope") == null, !el.hasAttribute("nope"))
        el.setAttribute("data-x", "1")
        assertTrue(el.hasAttribute("data-x"), el.getAttributeNames().contains("data-x"))
      },
      test("removeAttribute deletes it; toggleAttribute flips presence") {
        val d  = doc
        val el = d.createElement("div", "")
        el.setAttribute("hidden", "")
        el.removeAttribute("hidden")
        assertTrue(!el.hasAttribute("hidden"))
        val added = el.toggleAttribute("open", force = false)
        assertTrue(added, el.hasAttribute("open"))
      },
      test("classList reads/writes the class attribute — add/remove/contains observe setAttribute") {
        val d  = doc
        val el = d.createElement("div", "")
        el.setAttribute("class", "a b")
        assertTrue(el.classList.contains("a"), el.classList.contains("b"))
        el.classList.add("c")
        assertTrue(el.getAttribute("class").contains("c"))
      },
    ),
    suite("tree mutation")(
      test("appendChild links parent<->child and childNodes reflects it") {
        val d      = doc
        val parent = d.createElement("ul", "")
        val child  = d.createElement("li", "")
        parent.appendChild(child)
        assertTrue(
          child.parentNode.asInstanceOf[AnyRef] eq parent.asInstanceOf[AnyRef],
          parent.firstChild.asInstanceOf[AnyRef] eq child.asInstanceOf[AnyRef],
          parent.childNodes.length == 1,
        )
      },
      test("appendChild of an already-parented node RE-PARENTS it (the ForEach-reorder invariant)") {
        val d  = doc
        val p1 = d.createElement("div", "")
        val p2 = d.createElement("div", "")
        val c  = d.createElement("span", "")
        p1.appendChild(c)
        p2.appendChild(c) // must detach from p1 first
        assertTrue(
          p1.childNodes.length == 0,
          p2.childNodes.length == 1,
          c.parentNode.asInstanceOf[AnyRef] eq p2.asInstanceOf[AnyRef],
        )
      },
      test("insertBefore places a node ahead of the reference child; removeChild unlinks") {
        val d  = doc
        val ul = d.createElement("ul", "")
        val a  = d.createElement("li", "")
        val b  = d.createElement("li", "")
        ul.appendChild(b)
        ul.insertBefore(a, b)
        assertTrue(ul.firstChild.asInstanceOf[AnyRef] eq a.asInstanceOf[AnyRef])
        ul.removeChild(a)
        assertTrue(ul.childNodes.length == 1, a.parentNode == null)
      },
      test("replaceChild swaps in the new node and detaches the old") {
        val d   = doc
        val ul  = d.createElement("ul", "")
        val old = d.createElement("li", "")
        val neu = d.createElement("li", "")
        ul.appendChild(old)
        ul.replaceChild(neu, old)
        assertTrue(
          ul.childNodes.length == 1,
          ul.firstChild.asInstanceOf[AnyRef] eq neu.asInstanceOf[AnyRef],
          old.parentNode == null,
        )
      },
      test("previousSibling / nextSibling navigate the child list") {
        val d  = doc
        val ul = d.createElement("ul", "")
        val a  = d.createElement("li", "")
        val b  = d.createElement("li", "")
        ul.appendChild(a); ul.appendChild(b)
        assertTrue(
          a.nextSibling.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef],
          b.previousSibling.asInstanceOf[AnyRef] eq a.asInstanceOf[AnyRef],
          a.previousSibling == null,
        )
      },
    ),
    suite("textContent")(
      test("textContent aggregates descendant text; setting it replaces children with one text node") {
        val d  = doc
        val el = d.createElement("p", "")
        el.appendChild(d.createTextNode("hello "))
        val span = d.createElement("span", "")
        span.appendChild(d.createTextNode("world"))
        el.appendChild(span)
        assertTrue(el.textContent == "hello world")
        el.textContent = "replaced"
        assertTrue(el.textContent == "replaced", el.childNodes.length == 1)
      }
    ),
    suite("cloneNode")(
      test("a deep clone copies tag, attributes, and the child subtree — but is a distinct instance") {
        val d  = doc
        val el = d.createElement("div", "")
        el.setAttribute("id", "orig")
        el.appendChild(d.createTextNode("txt"))
        val clone = el.cloneNode(subtree = true)
        assertTrue(
          !(clone.asInstanceOf[AnyRef] eq el.asInstanceOf[AnyRef]),
          clone.asInstanceOf[Element].tagName == "div",
          clone.asInstanceOf[Element].getAttribute("id") == "orig",
          clone.textContent == "txt",
        )
      },
      test("a shallow clone copies the element but NOT its children") {
        val d  = doc
        val el = d.createElement("div", "")
        el.appendChild(d.createTextNode("txt"))
        val clone = el.cloneNode(subtree = false)
        assertTrue(clone.asInstanceOf[Element].tagName == "div", clone.childNodes.length == 0)
      },
    ),
    suite("querySelector family (over the css selector engine)")(
      test("querySelector finds the first descendant matching a compound selector") {
        val d    = doc
        val root = d.createElement("div", "")
        val a    = d.createElement("span", "")
        a.setAttribute("class", "target")
        val b = d.createElement("span", "")
        root.appendChild(a); root.appendChild(b)
        val found = root.querySelector(".target")
        assertTrue(found.asInstanceOf[AnyRef] eq a.asInstanceOf[AnyRef])
      },
      test("querySelectorAll returns every match; getElementsByTagName filters by tag") {
        val d    = doc
        val root = d.createElement("ul", "")
        root.appendChild(d.createElement("li", ""))
        root.appendChild(d.createElement("li", ""))
        root.appendChild(d.createElement("span", ""))
        assertTrue(
          root.querySelectorAll("li").length == 2,
          root.getElementsByTagName("li").length == 2,
        )
      },
      test("matches tests the element itself; a non-match returns false") {
        val d  = doc
        val el = d.createElement("div", "")
        el.setAttribute("class", "box")
        assertTrue(el.matches(".box"), !el.matches(".other"))
      },
      test("closest walks self-and-ancestors for the nearest match") {
        val d     = doc
        val outer = d.createElement("div", "")
        outer.setAttribute("class", "wrap")
        val middle = d.createElement("section", "")
        val inner  = d.createElement("span", "")
        outer.appendChild(middle); middle.appendChild(inner)
        val found = inner.closest(".wrap")
        assertTrue(found.asInstanceOf[AnyRef] eq outer.asInstanceOf[AnyRef])
      },
      test("a malformed selector yields null / empty / false rather than throwing") {
        val d  = doc
        val el = d.createElement("div", "")
        assertTrue(
          el.querySelector("!!!") == null,
          el.querySelectorAll("!!!").length == 0,
          !el.matches("!!!"),
        )
      },
    ),
    suite("CharacterData string ops")(
      test("appendData / insertData / deleteData / replaceData / substringData operate on data") {
        val d = doc
        val t = d.createTextNode("hello")
        t.appendData(" world")
        assertTrue(t.data == "hello world")
        t.insertData(5, ",")
        assertTrue(t.data == "hello, world")
        t.deleteData(5, 1)
        assertTrue(t.data == "hello world")
        t.replaceData(0, 5, "HELLO")
        assertTrue(t.data == "HELLO world")
        assertTrue(t.substringData(0, 5) == "HELLO")
      },
      test("out-of-range offsets clamp rather than throw") {
        val d = doc
        val t = d.createTextNode("abc")
        assertTrue(t.substringData(1, 999) == "bc", t.substringData(999, 1) == "")
      },
      test("remove() detaches a character-data node from its parent") {
        val d  = doc
        val el = d.createElement("p", "")
        val t  = d.createTextNode("x")
        el.appendChild(t)
        t.remove()
        assertTrue(el.childNodes.length == 0)
      },
    ),
    suite("document queries")(
      test("getElementById finds a descendant by its id attribute") {
        val d      = doc
        val html   = d.createElement("html", "")
        val body   = d.createElement("body", "")
        val target = d.createElement("div", "")
        target.setAttribute("id", "hit")
        html.appendChild(body); body.appendChild(target)
        d.appendChild(html)
        assertTrue(d.getElementById("hit").asInstanceOf[AnyRef] eq target.asInstanceOf[AnyRef])
      },
      test("activeElement is null off a real browser (no interactive focus in an in-memory tree)") {
        assertTrue(doc.activeElement == null)
      },
    ),
  )
end ElementMemorySpec
