package ascent.js

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.dom
import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** Two-way input binding, pinned by the caret-jump guard: [[Mount.setAttr]] writes `value` only when the element is NOT
  * focused OR the new value differs from the live one — otherwise the echo write would reset the caret.
  */
object MountTwoWaySpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (two-way input)")(
    test("a value-bound input renders the initial Squawk value as its live value property") {
      withParent { parent =>
        for
          v <- sq[AttrValue](AttrValue.Str("hi"))
          ui: UI[Any] = UI.Element(
            "input",
            Vector(Attr.ReactiveAttr("value", v)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
        yield
          val input = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(input.value.asInstanceOf[String] == "hi")
      }
    },
    test("when the focused input ALREADY has the value the Squawk emits, no write happens (caret preserved)") {
      withParent { parent =>
        for
          v <- sq[AttrValue](AttrValue.Str(""))
          ui: UI[Any] = UI.Element(
            "input",
            Vector(Attr.ReactiveAttr("value", v)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed {
            input.focus()
            input.value = "hello"
            input.setSelectionRange(2, 2)
          }
          _     <- v.set(AttrValue.Str("hello"))
          start <- ZIO.succeed(input.selectionStart.asInstanceOf[Int])
        yield assertTrue(start == 2)
      }
    },
    test("when the focused input has a DIFFERENT value, the Squawk's emit IS applied") {
      withParent { parent =>
        for
          v <- sq[AttrValue](AttrValue.Str("init"))
          ui: UI[Any] = UI.Element(
            "input",
            Vector(Attr.ReactiveAttr("value", v)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          _    <- ZIO.succeed { input.focus() }
          _    <- v.set(AttrValue.Str("driven"))
          live <- ZIO.succeed(input.value.asInstanceOf[String])
        yield assertTrue(live == "driven")
      }
    },
    test("when the input is NOT focused, the Squawk's emit always applies") {
      withParent { parent =>
        for
          v <- sq[AttrValue](AttrValue.Str(""))
          ui: UI[Any] = UI.Element(
            "input",
            Vector(Attr.ReactiveAttr("value", v)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- v.set(AttrValue.Str("first"))
          a <- ZIO.succeed(input.value.asInstanceOf[String])
          _ <- v.set(AttrValue.Str("second"))
          b <- ZIO.succeed(input.value.asInstanceOf[String])
        yield assertTrue(a == "first", b == "second")
      }
    },
    test("end-to-end echo loop: typing into a value+onInput-bound input does NOT collapse the caret") {
      // Wires both directions on one input: value = squawk, onInput = squawk.set(e.targetValue).
      withParent { parent =>
        for
          v <- sq("")
          handler     = (e: AscentEvent) => v.set(e.targetValue.getOrElse(""))
          ui: UI[Any] = UI.Element(
            "input",
            Vector(
              Attr.ReactiveAttr("value", v.map(s => AttrValue.Str(s))),
              Attr.EventHandler("input", handler),
            ),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed {
            el.focus()
            el.value = "abc"
            el.setSelectionRange(2, 2)
            val ev = js.Dynamic.newInstance(js.Dynamic.global.Event)("input", js.Dynamic.literal(bubbles = true))
            el.dispatchEvent(ev)
          }
          got   <- v.get.repeatUntil(_ == "abc")
          start <- ZIO.succeed(el.selectionStart.asInstanceOf[Int])
        yield assertTrue(got == "abc", start == 2)
      }
    },
  )
end MountTwoWaySpec
