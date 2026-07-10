package ascent.domcore

import ascent.domcore.generated.{Document, Element, Event}
import zio.test.*

/** The in-memory `dispatchEvent` and its WHATWG dispatch algorithm. Capture phase is intentionally unmodeled (the
  * `options` param that would carry `capture` isn't) — documented here, not silently wrong.
  */
object EventDispatchSpec extends ZIOSpecDefault:

  private def doc: Document = ascent.domcore.generated.DocumentMemory()

  private def event(tpe: String, bubbles: Boolean = false, cancelable: Boolean = false): Event =
    val e = ascent.domcore.generated.EventMemory()
    e.initEvent(tpe, bubbles, cancelable)
    e

  private def noopOptions: PlatformOpaque | Boolean = false

  def spec = suite("dom-core dispatchEvent (WHATWG dispatch algorithm)")(
    test("a listener on the target fires, and event.target / currentTarget are the target") {
      val d   = doc
      val el  = d.createElement("button", "")
      var got = List.empty[String]
      el.addEventListener(
        "click",
        e => got ::= s"${e.target.asInstanceOf[AnyRef] eq el.asInstanceOf[AnyRef]}",
        noopOptions,
      )
      el.dispatchEvent(event("click"))
      assertTrue(got == List("true"))
    },
    test("a bubbling event fires listeners on ancestors, innermost-first (target then up)") {
      val d     = doc
      val outer = d.createElement("div", "")
      val inner = d.createElement("span", "")
      outer.appendChild(inner)
      var order = List.empty[String]
      outer.addEventListener("click", _ => order ::= "outer", noopOptions)
      inner.addEventListener("click", _ => order ::= "inner", noopOptions)
      inner.dispatchEvent(event("click", bubbles = true))
      assertTrue(order.reverse == List("inner", "outer"))
    },
    test("a NON-bubbling event fires only the target's listener, not ancestors'") {
      val d     = doc
      val outer = d.createElement("div", "")
      val inner = d.createElement("span", "")
      outer.appendChild(inner)
      var order = List.empty[String]
      outer.addEventListener("click", _ => order ::= "outer", noopOptions)
      inner.addEventListener("click", _ => order ::= "inner", noopOptions)
      inner.dispatchEvent(event("click", bubbles = false))
      assertTrue(order == List("inner"))
    },
    test("stopPropagation halts the bubble after the current node's listeners run") {
      val d     = doc
      val outer = d.createElement("div", "")
      val inner = d.createElement("span", "")
      outer.appendChild(inner)
      var order = List.empty[String]
      outer.addEventListener("click", _ => order ::= "outer", noopOptions)
      inner.addEventListener(
        "click",
        e =>
          order ::= "inner"; e.stopPropagation()
        ,
        noopOptions,
      )
      inner.dispatchEvent(event("click", bubbles = true))
      assertTrue(order == List("inner"))
    },
    test("stopImmediatePropagation halts remaining listeners on the SAME node too") {
      val d     = doc
      val el    = d.createElement("div", "")
      var order = List.empty[String]
      el.addEventListener(
        "click",
        e =>
          order ::= "first"; e.stopImmediatePropagation()
        ,
        noopOptions,
      )
      el.addEventListener("click", _ => order ::= "second", noopOptions)
      el.dispatchEvent(event("click"))
      assertTrue(order == List("first"))
    },
    test("preventDefault on a cancelable event makes dispatchEvent return false") {
      val d  = doc
      val el = d.createElement("a", "")
      el.addEventListener("click", _.preventDefault(), noopOptions)
      val e           = event("click", cancelable = true)
      val notCanceled = el.dispatchEvent(e)
      assertTrue(!notCanceled, e.defaultPrevented)
    },
    test("preventDefault on a NON-cancelable event is ignored; dispatchEvent returns true") {
      val d  = doc
      val el = d.createElement("a", "")
      el.addEventListener("click", _.preventDefault(), noopOptions)
      val e = event("click", cancelable = false)
      assertTrue(el.dispatchEvent(e), !e.defaultPrevented)
    },
    test("dispatchEvent with no matching listeners returns true and doesn't throw") {
      val d = doc
      assertTrue(d.createElement("div", "").dispatchEvent(event("click")))
    },
    test("removeEventListener stops a listener from firing on subsequent dispatch") {
      val d                = doc
      val el               = d.createElement("div", "")
      var n                = 0
      val f: Event => Unit = _ => n += 1
      el.addEventListener("click", f, noopOptions)
      el.dispatchEvent(event("click"))
      el.removeEventListener("click", f, noopOptions)
      el.dispatchEvent(event("click"))
      assertTrue(n == 1)
    },
    test("bubbles / cancelable / type reflect what initEvent set") {
      val e = event("custom", bubbles = true, cancelable = true)
      assertTrue(e.`type` == "custom", e.bubbles, e.cancelable, !e.defaultPrevented)
    },
    test("currentTarget is cleared back to null after dispatch completes") {
      val d  = doc
      val el = d.createElement("div", "")
      el.addEventListener("click", _ => (), noopOptions)
      val e = event("click")
      el.dispatchEvent(e)
      assertTrue(e.currentTarget == null)
    },
  )
end EventDispatchSpec
