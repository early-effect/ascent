package ascent.js

import ascent.ast.{Attr, UI}
import ascent.dom
import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** Reactive binding: each boundary subscribes its [[Squawk]] and patches exactly the affected DOM property on emit — no
  * subtree rebuilds.
  */
object MountReactiveSpec extends ZIOSpecDefault:

  /** Throwaway parent per test, removed afterwards so suites don't share DOM state. */
  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (reactive)")(
    test("ReactiveText emits the initial value as a real text node") {
      withParent { parent =>
        for
          name <- sq("Alice")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          _ <- AscentApp.mount(ui, parent)
        yield
          val span = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(span.textContent.asInstanceOf[String] == "Alice")
      }
    },
    test("setting the Squawk patches ONLY the bound text node, in place") {
      withParent { parent =>
        for
          name <- sq("Alice")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          _ <- AscentApp.mount(ui, parent)
          spanEl   = parent.asInstanceOf[js.Dynamic].firstChild
          textNode = spanEl.firstChild
          before   = textNode.data.asInstanceOf[String]
          _ <- name.set("Bob")
          after             = textNode.data.asInstanceOf[String]
          stillSameTextNode = spanEl.firstChild eq textNode
          stillSameSpan     = parent.asInstanceOf[js.Dynamic].firstChild eq spanEl
        yield assertTrue(
          before == "Alice",
          after == "Bob",
          stillSameTextNode.asInstanceOf[Boolean],
          stillSameSpan.asInstanceOf[Boolean],
        )
      }
    },
    test("ReactiveText dedup: setting an Eq-equal value does not touch the DOM") {
      withParent { parent =>
        for
          name <- sq("Alice")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          _ <- AscentApp.mount(ui, parent)
          textNode = parent.asInstanceOf[js.Dynamic].firstChild.firstChild
          _ <- name.set("Alice")
          _ <- name.set("Alice")
          v = textNode.data.asInstanceOf[String]
        yield assertTrue(v == "Alice")
      }
    },
    test("cleanup cancels the Squawk subscription so later sets do not patch the DOM") {
      withParent { parent =>
        for
          name <- sq("Alice")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          cleanup <- AscentApp.mount(ui, parent)
          textNode = parent.asInstanceOf[js.Dynamic].firstChild.firstChild
          _ <- cleanup.cancelAll
          _ <- name.set("Bob")
          v = textNode.data.asInstanceOf[String]
        yield assertTrue(v == "Alice")
      }
    },
    test("ReactiveAttr patches the bound string attribute on each emit") {
      withParent { parent =>
        for
          cls <- sq[AttrValue](AttrValue.Str("foo"))
          ui: UI[Any] = UI.Element(
            tag = "div",
            attrs = Vector(Attr.ReactiveAttr("class", cls)),
            children = Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
          a <- ZIO.succeed(el.getAttribute("class").asInstanceOf[String])
          _ <- cls.set(AttrValue.Str("bar"))
          b <- ZIO.succeed(el.getAttribute("class").asInstanceOf[String])
        yield assertTrue(a == "foo", b == "bar")
      }
    },
    test("ReactiveAttr that toggles to Absent removes the attribute (presence-coded boolean)") {
      withParent { parent =>
        for
          required <- sq[AttrValue](AttrValue.Str(""))
          ui: UI[Any] = UI.Element(
            tag = "input",
            attrs = Vector(Attr.ReactiveAttr("required", required)),
            children = Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
          present <- ZIO.succeed(el.hasAttribute("required").asInstanceOf[Boolean])
          _       <- required.set(AttrValue.Absent)
          absent  <- ZIO.succeed(el.hasAttribute("required").asInstanceOf[Boolean])
        yield assertTrue(present, !absent)
      }
    },
    test("ReactiveAttr on `value` writes the property so live state is updated") {
      withParent { parent =>
        for
          v <- sq[AttrValue](AttrValue.Str("first"))
          ui: UI[Any] = UI.Element(
            tag = "input",
            attrs = Vector(Attr.ReactiveAttr("value", v)),
            children = Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          a <- ZIO.succeed(input.value.asInstanceOf[String])
          _ <- v.set(AttrValue.Str("second"))
          b <- ZIO.succeed(input.value.asInstanceOf[String])
        yield assertTrue(a == "first", b == "second")
      }
    },
    test("multiple sibling ReactiveTexts each track their own Squawk independently") {
      withParent { parent =>
        for
          a <- sq("A1")
          b <- sq("B1")
          ui: UI[Any] = UI.Element(
            tag = "div",
            attrs = Vector.empty,
            children = Vector(
              UI.Element("span", Vector.empty, Vector(UI.ReactiveText(a))),
              UI.Element("span", Vector.empty, Vector(UI.ReactiveText(b))),
            ),
          )
          _ <- AscentApp.mount(ui, parent)
          spans = parent.asInstanceOf[js.Dynamic].firstChild.children
          _ <- a.set("A2")
          a2 = spans.item(0).textContent.asInstanceOf[String]
          b1 = spans.item(1).textContent.asInstanceOf[String]
          _ <- b.set("B2")
          a2_ = spans.item(0).textContent.asInstanceOf[String]
          b2  = spans.item(1).textContent.asInstanceOf[String]
        yield assertTrue(
          a2 == "A2",
          b1 == "B1",
          a2_ == "A2",
          b2 == "B2",
        )
      }
    },
    test("counter: clicking the buttons updates only the bound text node, sibling buttons unchanged") {
      // Handlers are forked, so completion is observed by waiting for the count Squawk to reach its value.
      withParent { parent =>
        for
          count <- sq(0)
          inc         = (_: ascent.ast.AscentEvent) => count.update(_ + 1)
          dec         = (_: ascent.ast.AscentEvent) => count.update(_ - 1)
          ui: UI[Any] = UI.Element(
            tag = "div",
            attrs = Vector.empty,
            children = Vector(
              UI.Element("button", Vector(Attr.EventHandler("click", dec)), Vector(UI.Text("-"))),
              UI.Element("span", Vector.empty, Vector(UI.ReactiveText(count.map(_.toString)))),
              UI.Element("button", Vector(Attr.EventHandler("click", inc)), Vector(UI.Text("+"))),
            ),
          )
          _ <- AscentApp.mount(ui, parent)
          root    = parent.asInstanceOf[js.Dynamic].firstChild
          decBtn  = root.children.item(0)
          span    = root.children.item(1)
          incBtn  = root.children.item(2)
          decId0  = decBtn.asInstanceOf[js.Any]
          incId0  = incBtn.asInstanceOf[js.Any]
          spanId0 = span.asInstanceOf[js.Any]
          textId0 = span.firstChild.asInstanceOf[js.Any]
          _ <- ZIO.succeed(incBtn.click())
          _ <- ZIO.succeed(incBtn.click())
          _ <- ZIO.succeed(decBtn.click())
          _ <- count.get.repeatUntil(_ == 1)
          v = span.textContent.asInstanceOf[String]
        yield assertTrue(
          v == "1",
          (decBtn.asInstanceOf[js.Any] eq decId0),
          (incBtn.asInstanceOf[js.Any] eq incId0),
          (span.asInstanceOf[js.Any] eq spanId0),
          (span.firstChild.asInstanceOf[js.Any] eq textId0),
        )
      }
    },
  )
end MountReactiveSpec
