package ascent.dom.accessors

import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** WebIDL's nullable members (`T?`) read as `Option` against jsdom, under a bare `import ascent.dom`: this package sits
  * outside `ascent.dom`, so each accessor here resolves through `PlatformObject`'s implicit scope alone.
  */
object NullableAccessorsSpec extends ZIOSpecDefault:

  private def same(a: js.Any, b: js.Any): Boolean = js.special.strictEquals(a, b)

  def spec = suite("Nullable DOM members as Option")(
    test("parentNode is None while detached, the parent once appended, and None again once removed") {
      ZIO.succeed {
        val parent   = dom.document.createElement("div")
        val child    = dom.document.createElement("span")
        val detached = child.parentNode
        parent.appendChild(child)
        val attached = child.parentNode
        parent.removeChild(child)
        assertTrue(detached.isEmpty, attached.exists(same(_, parent)), child.parentNode.isEmpty)
      }
    },
    test("getAttribute is None when absent and round-trips any value setAttribute writes") {
      check(Gen.string) { value =>
        val el     = dom.document.createElement("div")
        val absent = el.getAttribute("data-x")
        el.setAttribute("data-x", value)
        assertTrue(absent.isEmpty, el.getAttribute("data-x").contains(value))
      }
    },
    test("textContent written as Some reads back the same text") {
      check(Gen.string) { text =>
        val el = dom.document.createElement("p")
        el.textContent = Some(text)
        assertTrue(el.textContent.contains(text))
      }
    },
    test("textContent written as None clears the element, as the empty string does") {
      check(Gen.alphaNumericString) { text =>
        val cleared = dom.document.createElement("p")
        val emptied = dom.document.createElement("p")
        cleared.textContent = Some(text)
        emptied.textContent = Some(text)
        cleared.textContent = None
        emptied.textContent = Some("")
        assertTrue(cleared.textContent == emptied.textContent, cleared.firstChild.isEmpty)
      }
    },
    test("querySelector is None without a match and the matching element with one") {
      ZIO.succeed {
        val root  = dom.document.createElement("div")
        val none  = root.querySelector(".hit")
        val child = dom.document.createElement("em")
        child.setAttribute("class", "hit")
        root.appendChild(child)
        assertTrue(none.isEmpty, root.querySelector(".hit").exists(same(_, child)))
      }
    },
    test("the document has a body and a root element") {
      ZIO.succeed(assertTrue(dom.document.body.isDefined, dom.document.documentElement.isDefined))
    },
    suite("getContext keyed by CanvasContextId")(
      test("TwoD answers a 2D context for the same canvas") {
        ZIO.succeed {
          val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
          val ctx    = canvas.getContext(dom.CanvasContextId.TwoD)
          assertTrue(ctx.exists(c => same(c.canvas, canvas)))
        }
      },
      test("TwoD takes its settings dictionary") {
        ZIO.succeed {
          val canvas   = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
          val settings = new dom.CanvasRenderingContext2DSettings:
            alpha = false
          assertTrue(canvas.getContext(dom.CanvasContextId.TwoD, settings).isDefined)
        }
      },
      test("a context the browser cannot make is None (jsdom has no WebGL)") {
        ZIO.succeed {
          val canvas = dom.document.createElement("canvas").asInstanceOf[dom.HTMLCanvasElement]
          assertTrue(canvas.getContext(dom.CanvasContextId.WebGL).isEmpty)
        }
      },
    ),
  ) @@ TestAspect.timeout(60.seconds)
end NullableAccessorsSpec
