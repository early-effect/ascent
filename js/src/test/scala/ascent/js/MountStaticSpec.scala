package ascent.js

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.dom
import ascent.domtypes.AttrValue
import zio.*
import zio.test.*

import scala.scalajs.js

/** Mounting the static AST subset (Element, Text, Empty, Fragment, static attrs, event handlers) into real DOM, pinning
  * attr-vs-property routing and event-listener detach on cleanup.
  */
object MountStaticSpec extends ZIOSpecDefault:

  /** A throwaway parent created and removed inside each test, so suites don't share DOM state. */
  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (static)")(
    test("a single Text node renders as a real text child of the parent") {
      withParent { parent =>
        for _ <- AscentApp.mount(UI.Text("hello"), parent)
        yield
          val firstChild = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(
            firstChild.nodeType.asInstanceOf[Int] == 3, // TEXT_NODE
            firstChild.data.asInstanceOf[String] == "hello",
          )
      }
    },
    test("an Element renders with the given tag and string attrs via setAttribute") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "div",
          attrs = Vector(Attr.StaticAttr("id", AttrValue.Str("x"))),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(
            el.tagName.asInstanceOf[String].toLowerCase == "div",
            el.getAttribute("id").asInstanceOf[String] == "x",
          )
      }
    },
    test("multiple `class` attrs on the same element MERGE (space-joined), they don't clobber") {
      // class is the only attribute that composes: two contributors (e.g. a CssClass and a tooltip)
      // must both land. All other attrs are single-valued, where setAttribute's last-write-wins is correct.
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "div",
          attrs = Vector(
            Attr.StaticAttr("class", AttrValue.Str("first")),
            Attr.StaticAttr("class", AttrValue.Str("second")),
            Attr.StaticAttr("class", AttrValue.Str("third")),
          ),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el  = parent.asInstanceOf[js.Dynamic].firstChild
          val cls = el.getAttribute("class").asInstanceOf[String]
          assertTrue(
            cls.split(" ").toSet == Set("first", "second", "third")
          )
      }
    },
    test("class merge: empty/Absent class attrs are skipped (no stray spaces)") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "div",
          attrs = Vector(
            Attr.StaticAttr("class", AttrValue.Str("real")),
            Attr.StaticAttr("class", AttrValue.Str("")),
            Attr.StaticAttr("class", AttrValue.Absent),
            Attr.StaticAttr("class", AttrValue.Str("also-real")),
          ),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el  = parent.asInstanceOf[js.Dynamic].firstChild
          val cls = el.getAttribute("class").asInstanceOf[String]
          assertTrue(cls == "real also-real")
      }
    },
    test("a presence-coded boolean attr (Absent) leaves the attribute unset") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "input",
          attrs = Vector(Attr.StaticAttr("disabled", AttrValue.Absent)),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          val v  = el.getAttribute("disabled")
          assertTrue(v == null || js.isUndefined(v))
      }
    },
    test("a present boolean attr writes an empty-string attribute (the canonical HTML form)") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "input",
          attrs = Vector(Attr.StaticAttr("disabled", AttrValue.Str(""))),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(el.hasAttribute("disabled").asInstanceOf[Boolean])
      }
    },
    test("input value/checked are set as PROPERTIES so reads of the live state reflect them") {
      // setAttribute("value", …) only sets the INITIAL attribute; the live el.value property stays empty.
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "input",
          attrs = Vector(
            Attr.StaticAttr("value", AttrValue.Str("typed")),
            Attr.StaticAttr("checked", AttrValue.Bool(true)),
          ),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(
            el.value.asInstanceOf[String] == "typed",
            el.checked.asInstanceOf[Boolean] == true,
          )
      }
    },
    test("`value` on a NON-form element sets the content attribute (backend-parity: not the .value property)") {
      // The property path is for form controls only (nominal input/textarea/select). Regression: JsDomOps used a
      // STRUCTURAL !isUndefined(el.value) check — true for <button>/<option>/… — diverging from the in-memory backend.
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "button",
          attrs = Vector(Attr.StaticAttr("value", AttrValue.Str("go"))),
          children = Vector.empty,
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val el = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(el.getAttribute("value").asInstanceOf[String] == "go")
      }
    },
    test("nested children render in source order") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          tag = "div",
          attrs = Vector.empty,
          children = Vector(
            UI.Element("span", Vector.empty, Vector(UI.Text("a"))),
            UI.Element("span", Vector.empty, Vector(UI.Text("b"))),
            UI.Element("span", Vector.empty, Vector(UI.Text("c"))),
          ),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val children = parent.asInstanceOf[js.Dynamic].firstChild.children
          assertTrue(
            children.length.asInstanceOf[Int] == 3,
            children.item(0).textContent.asInstanceOf[String] == "a",
            children.item(2).textContent.asInstanceOf[String] == "c",
          )
      }
    },
    test("Empty produces no DOM at all (no comment, no element)") {
      withParent { parent =>
        for _ <- AscentApp.mount(UI.Empty, parent)
        yield
          val cs = parent.asInstanceOf[js.Dynamic].childNodes
          assertTrue(cs.length.asInstanceOf[Int] == 0)
      }
    },
    test("Fragment splices its children into the parent at the insertion point") {
      withParent { parent =>
        val ui: UI[Any] = UI.Fragment(Vector(UI.Text("a"), UI.Text("b"), UI.Text("c")))
        for _ <- AscentApp.mount(ui, parent)
        yield
          val cs    = parent.asInstanceOf[js.Dynamic].childNodes
          val texts = (0 until cs.length.asInstanceOf[Int]).flatMap { i =>
            val n = cs.item(i)
            if n.nodeType.asInstanceOf[Int] == 3 then Some(n.data.asInstanceOf[String]) else None
          }
          assertTrue(texts.toList == List("a", "b", "c"))
      }
    },
    test("an EventHandler attaches a real listener that runs the handler effect when the event fires") {
      // Handlers run forked on the macrotask scheduler, so a Queue (not a Ref read) waits for each invocation.
      withParent { parent =>
        for
          fires <- Queue.unbounded[Unit]
          handler     = (_: AscentEvent) => fires.offer(()).unit
          ui: UI[Any] = UI.Element(
            tag = "button",
            attrs = Vector(Attr.EventHandler("click", handler)),
            children = Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _         <- ZIO.succeed(btn.click())
          _         <- ZIO.succeed(btn.click())
          _         <- fires.take *> fires.take
          remaining <- fires.size
        yield assertTrue(remaining == 0)
      }
    },
    test("cleanup detaches event listeners so they no longer fire") {
      withParent { parent =>
        for
          fires <- Queue.unbounded[Unit]
          handler     = (_: AscentEvent) => fires.offer(()).unit
          ui: UI[Any] = UI.Element(
            tag = "button",
            attrs = Vector(Attr.EventHandler("click", handler)),
            children = Vector.empty,
          )
          cleanup <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed(btn.click())
          _ <- fires.take
          _ <- cleanup.cancelAll
          _ <- ZIO.succeed(btn.click())
          // Yield so a still-attached (buggy) listener would have a turn to enqueue before we check.
          _         <- ZIO.yieldNow
          remaining <- fires.size
        yield assertTrue(remaining == 0)
      }
    },
    test("AscentEvent.targetValue surfaces the input's live value to the handler") {
      withParent { parent =>
        for
          captured <- Promise.make[Nothing, Option[String]]
          handler     = (e: AscentEvent) => captured.succeed(e.targetValue).unit
          ui: UI[Any] = UI.Element(
            tag = "input",
            attrs = Vector(Attr.EventHandler("input", handler)),
            children = Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed(input.value = "hi")
          _ <- ZIO.succeed {
            val ev = js.Dynamic.newInstance(js.Dynamic.global.Event)(
              "input",
              js.Dynamic.literal(bubbles = true),
            )
            input.dispatchEvent(ev)
          }
          v <- captured.await
        yield assertTrue(v == Some("hi"))
      }
    },
  )
end MountStaticSpec
