package ascent.dom

import zio.*
import zio.test.*

import scala.scalajs.js

/** Exercises the hand-written engine facade end-to-end against jsdom: bugs in `@js.native` signatures are silent at
  * compile time and only surface when the JS runtime mismatches the declared type.
  */
object EngineFacadeSpec extends ZIOSpecDefault:

  def spec = suite("Engine DOM facade")(
    test("document is reachable and createElement/createTextNode/createComment build real nodes") {
      ZIO.succeed {
        val el = document.createElement("div")
        val tx = document.createTextNode("hello")
        val cm = document.createComment("anchor")
        // The @js.native types are opaque, so read nodeType through js.Dynamic to confirm the runtime tag.
        val nt: js.Dynamic = el.asInstanceOf[js.Dynamic]
        assertTrue(
          nt.nodeType.asInstanceOf[Int] == 1,                          // ELEMENT_NODE
          tx.asInstanceOf[js.Dynamic].nodeType.asInstanceOf[Int] == 3, // TEXT_NODE
          cm.asInstanceOf[js.Dynamic].nodeType.asInstanceOf[Int] == 8, // COMMENT_NODE
        )
      }
    },
    test("Text.data is a writable property: setting it updates the rendered text") {
      ZIO.succeed {
        val tx = document.createTextNode("first")
        tx.data = "second"
        assertTrue(tx.data == "second")
      }
    },
    test("Element parent/sibling/child manipulation: appendChild, insertBefore, removeChild, replaceChild") {
      ZIO.succeed {
        val parent = document.createElement("div")
        val a      = document.createElement("span")
        val b      = document.createElement("span")
        val c      = document.createElement("span")
        parent.appendChild(a)
        parent.appendChild(c)
        parent.insertBefore(b, c) // -> a, b, c
        // Index into children via js.Dynamic: the facade deliberately omits it.
        val children = parent.asInstanceOf[js.Dynamic].children
        assertTrue(children.length.asInstanceOf[Int] == 3) &&
        ZIO.succeed {
          parent.removeChild(b) // -> a, c
          assertTrue(children.length.asInstanceOf[Int] == 2)
        }.runUnsafely
        val replacement = document.createElement("p")
        parent.replaceChild(replacement, a) // -> replacement, c
        assertTrue(
          children.length.asInstanceOf[Int] == 2,
          children.item(0).tagName.asInstanceOf[String].toLowerCase == "p",
        )
      }
    },
    test("Element.setAttribute and removeAttribute round-trip for an arbitrary string attr") {
      ZIO.succeed {
        val el = document.createElement("div")
        el.setAttribute("data-x", "hello")
        val present = el.asInstanceOf[js.Dynamic].getAttribute("data-x").asInstanceOf[String]
        el.removeAttribute("data-x")
        val absent = el.asInstanceOf[js.Dynamic].getAttribute("data-x")
        assertTrue(present == "hello", absent == null || js.isUndefined(absent))
      }
    },
    test("HTMLInputElement value and checked are PROPERTIES (writable, reflect immediately)") {
      ZIO.succeed {
        val input = document.createElement("input").asInstanceOf[HTMLInputElement]
        input.value = "typed"
        input.checked = true
        assertTrue(input.value == "typed", input.checked == true)
      }
    },
    test("addEventListener/removeEventListener attach and detach, and the listener fires") {
      ZIO.succeed {
        val el                                   = document.createElement("button")
        var clicks                               = 0
        val listener: js.Function1[js.Any, Unit] = (_: js.Any) => clicks += 1
        el.addEventListener("click", listener)
        el.asInstanceOf[js.Dynamic].click() // jsdom dispatches synchronously
        val firstCount = clicks
        el.removeEventListener("click", listener)
        el.asInstanceOf[js.Dynamic].click()
        assertTrue(firstCount == 1, clicks == 1)
      }
    },
    test("Node.parentNode and nextSibling traverse the tree as expected") {
      ZIO.succeed {
        val parent = document.createElement("div")
        val a      = document.createElement("span")
        val b      = document.createElement("span")
        parent.appendChild(a)
        parent.appendChild(b)
        // parentNode/nextSibling are opaque Node references, so identity-check via js.Any.
        val aParent = a.parentNode.asInstanceOf[js.Any]
        val aNext   = a.nextSibling.asInstanceOf[js.Any]
        assertTrue(aParent eq parent.asInstanceOf[js.Any], aNext eq b.asInstanceOf[js.Any])
      }
    },
    test("Document.activeElement reflects focus (used by the controlled-input caret-jump guard)") {
      ZIO.succeed {
        val parent = document.createElement("div")
        document.asInstanceOf[js.Dynamic].body.appendChild(parent)
        val input = document.createElement("input").asInstanceOf[HTMLInputElement]
        parent.appendChild(input)
        input.asInstanceOf[js.Dynamic].focus()
        val focused = document.activeElement.asInstanceOf[js.Any] eq input.asInstanceOf[js.Any]
        document.asInstanceOf[js.Dynamic].body.removeChild(parent)
        assertTrue(focused)
      }
    },
  )

  // Runs a UIO synchronously inside an already-entered ZIO.succeed block (the chained appendChild/removeChild case).
  extension [A](z: UIO[A])
    private def runUnsafely: A = Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z).getOrThrow())
end EngineFacadeSpec
