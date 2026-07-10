package ascent.js

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.domcore.generated.{Element, Node}
import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

/** Platform-neutral parity tests for the ported [[Mount]] engine, run against dom-core's in-memory backend on the JVM;
  * expectations come from the WHATWG DOM contract and reconciler invariants, never from what this engine emits.
  */
object MountInMemorySpec extends ZIOSpecDefault:

  /** A fresh in-memory backend + root element per test (no shared state), returning the root and its Subscriptions. A
    * fresh per-render [[ascent.css.StyleRegistry]] over the noop sink satisfies Mount's requirement (styles aren't
    * under test here).
    */
  private def mountInto[R](ui: UI[R]): URIO[R, (Node, Subscriptions)] =
    val (doc, ops0)    = InMemoryDomOps.make()
    given DomOps[Node] = ops0
    // Node (not the narrower Element createElement returns) so Mount infers N = Node and finds the DomOps[Node] given.
    val root: Node = doc.createElement("div", "")
    Mount
      .mount(ui, root)
      .provideSomeLayer[R](ascent.css.StyleRegistry.scoped(ascent.css.StyleSink.noop))
      .map(cleanup => (root, cleanup))

  /** The child nodes of `n` as a Vector (typed domcore access, not js.Dynamic). */
  private def childList(n: Node): Vector[Node] =
    val cs = n.childNodes
    (0 until cs.length).map(cs.item).toVector

  /** firstChild as an Element (fails the test loudly if it isn't one). */
  private def firstElement(parent: Node): Element =
    parent.childNodes.item(0).asInstanceOf[Element]

  private def li(text: String): UI[Any] =
    UI.Element("li", Vector.empty, Vector(UI.Text(text)))

  private def feUi(items: ascent.squawk.Squawk[Seq[String]]): UI[Any] =
    UI.Element("ul", Vector.empty, Vector(UI.ForEach(items, identity, li)))

  /** Visible (non-comment) child texts of parent.firstChild. */
  private def visibleTexts(parent: Node): List[String] =
    childList(firstElement(parent)).filter(_.nodeType != 8).map(_.textContent).toList

  /** Visible child nodes as identity references (proves reuse). */
  private def visibleNodes(parent: Node): Vector[Node] =
    childList(firstElement(parent)).filter(_.nodeType != 8)

  def spec = suite("Mount (in-memory backend, behavior parity)")(
    suite("static")(
      test("a single Text renders as a real text child (nodeType 3, data)") {
        for (root, _) <- mountInto(UI.Text("hello"))
        yield
          val t = root.childNodes.item(0)
          assertTrue(t.nodeType == 3, t.asInstanceOf[ascent.domcore.generated.Text].data == "hello")
      },
      test("an Element renders with the given tag and a string attr via setAttribute") {
        val ui: UI[Any] = UI.Element("div", Vector(Attr.StaticAttr("id", AttrValue.Str("x"))), Vector.empty)
        for (root, _) <- mountInto(ui)
        yield
          val el = firstElement(root)
          assertTrue(el.tagName == "div", el.getAttribute("id") == "x")
      },
      test("multiple `class` attrs on one element MERGE (compose), not clobber") {
        // Asserted as a set: a class merge has no guaranteed token order, so order isn't pinned.
        val ui: UI[Any] = UI.Element(
          "div",
          Vector(
            Attr.StaticAttr("class", AttrValue.Str("first")),
            Attr.StaticAttr("class", AttrValue.Str("second")),
            Attr.StaticAttr("class", AttrValue.Str("third")),
          ),
          Vector.empty,
        )
        for (root, _) <- mountInto(ui)
        yield
          val cls = firstElement(root).getAttribute("class")
          assertTrue(cls.split(" ").filter(_.nonEmpty).toSet == Set("first", "second", "third"))
      },
      test("empty/Absent class attrs are skipped (no stray tokens)") {
        val ui: UI[Any] = UI.Element(
          "div",
          Vector(
            Attr.StaticAttr("class", AttrValue.Str("real")),
            Attr.StaticAttr("class", AttrValue.Str("")),
            Attr.StaticAttr("class", AttrValue.Absent),
            Attr.StaticAttr("class", AttrValue.Str("also-real")),
          ),
          Vector.empty,
        )
        for (root, _) <- mountInto(ui)
        yield
          val cls = firstElement(root).getAttribute("class")
          assertTrue(cls.split(" ").filter(_.nonEmpty).toSet == Set("real", "also-real"))
      },
      test("a present boolean attr writes an empty-string attribute (canonical HTML form)") {
        val ui: UI[Any] = UI.Element("input", Vector(Attr.StaticAttr("disabled", AttrValue.Str(""))), Vector.empty)
        for (root, _) <- mountInto(ui)
        yield assertTrue(firstElement(root).hasAttribute("disabled"))
      },
      test("an Absent boolean attr leaves the attribute unset") {
        val ui: UI[Any] = UI.Element("input", Vector(Attr.StaticAttr("disabled", AttrValue.Absent)), Vector.empty)
        for (root, _) <- mountInto(ui)
        yield assertTrue(!firstElement(root).hasAttribute("disabled"))
      },
      test("input value/checked are set as live PROPERTIES, not attributes (two-way-binding contract)") {
        // value/checked are IDL properties; setAttribute would only seed defaultValue.
        val ui: UI[Any] = UI.Element(
          "input",
          Vector(Attr.StaticAttr("value", AttrValue.Str("typed")), Attr.StaticAttr("checked", AttrValue.Bool(true))),
          Vector.empty,
        )
        for (root, _) <- mountInto(ui)
        yield
          val input = firstElement(root).asInstanceOf[ascent.domcore.generated.HTMLInputElement]
          assertTrue(input.value == "typed", input.checked == true)
      },
      test("nested children render in source order") {
        val ui: UI[Any] = UI.Element(
          "div",
          Vector.empty,
          Vector(
            UI.Element("span", Vector.empty, Vector(UI.Text("a"))),
            UI.Element("span", Vector.empty, Vector(UI.Text("b"))),
            UI.Element("span", Vector.empty, Vector(UI.Text("c"))),
          ),
        )
        for (root, _) <- mountInto(ui)
        yield
          val kids = childList(firstElement(root))
          assertTrue(kids.length == 3, kids(0).textContent == "a", kids(2).textContent == "c")
      },
      test("Empty produces no DOM at all") {
        for (root, _) <- mountInto(UI.Empty)
        yield assertTrue(root.childNodes.length == 0)
      },
      test("Fragment splices its children as siblings, no wrapper") {
        val ui: UI[Any] = UI.Fragment(Vector(UI.Text("a"), UI.Text("b"), UI.Text("c")))
        for (root, _) <- mountInto(ui)
        yield
          val texts = childList(root).filter(_.nodeType == 3).map(_.textContent)
          assertTrue(texts.toList == List("a", "b", "c"))
      },
    ),
    suite("reactive")(
      test("ReactiveText mutates the SAME text node in place (identity preserved across .set)") {
        for
          name <- sq("Alice")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          (root, _) <- mountInto(ui)
          span   = firstElement(root)
          before = span.childNodes.item(0)
          _ <- name.set("Bob")
          after = span.childNodes.item(0)
        yield assertTrue(
          before.asInstanceOf[AnyRef] eq after.asInstanceOf[AnyRef], // same node
          after.asInstanceOf[ascent.domcore.generated.Text].data == "Bob",
        )
      },
      test("ReactiveText cleanup cancels the subscription (observer count drops to 0)") {
        for
          name <- sq("x")
          ui: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(name)))
          (_, cleanup) <- mountInto(ui)
          during       <- name.observerCount
          _            <- cleanup.cancelAll
          after        <- name.observerCount
        yield assertTrue(during == 1, after == 0)
      },
      test("a reactive string attr patches the attribute value on emit") {
        for
          title <- sq("one")
          ui: UI[Any] = UI.Element("div", Vector(Attr.ReactiveAttr("title", title.map(AttrValue.Str(_)))), Vector.empty)
          (root, _) <- mountInto(ui)
          el     = firstElement(root)
          before = el.getAttribute("title")
          _ <- title.set("two")
          after = el.getAttribute("title")
        yield assertTrue(before == "one", after == "two")
      },
    ),
    suite("dynamic")(
      test("ReactiveChild swaps the subtree and tears down the previous content's observers") {
        for
          inner <- sq("hi")
          which <- sq(true)
          ui: UI[Any] = UI.Element(
            "div",
            Vector.empty,
            Vector(
              UI.ReactiveChild(
                which.map(b =>
                  if b then UI.Element("p", Vector.empty, Vector(UI.ReactiveText(inner)))
                  else UI.Empty
                )
              )
            ),
          )
          (_, _) <- mountInto(ui)
          n0     <- inner.observerCount // 1 while the <p> is mounted
          _      <- which.set(false)    // swap to Empty must cancel the ReactiveText observer
          n1     <- inner.observerCount
        yield assertTrue(n0 == 1, n1 == 0)
      },
      test("When mounts the body on true and removes all DOM on false") {
        for
          cond <- sq(false)
          ui: UI[Any] = UI.Element("div", Vector.empty, Vector(UI.When(cond, () => li("shown"))))
          (root, _) <- mountInto(ui)
          emptyChildren = childList(firstElement(root)).count(_.nodeType != 8)
          _ <- cond.set(true)
          afterTrue = visibleTexts(root)
          _ <- cond.set(false)
          afterFalse = childList(firstElement(root)).count(_.nodeType != 8)
        yield assertTrue(emptyChildren == 0, afterTrue == List("shown"), afterFalse == 0)
      },
    ),
    suite("forEach")(
      test("initial render builds one node per item, in order") {
        for
          items     <- sq(Seq("a", "b", "c"))
          (root, _) <- mountInto(feUi(items))
        yield assertTrue(visibleTexts(root) == List("a", "b", "c"))
      },
      test("appending items adds nodes, preserving order") {
        for
          items     <- sq(Seq("a", "b"))
          (root, _) <- mountInto(feUi(items))
          _         <- items.set(Seq("a", "b", "c"))
        yield assertTrue(visibleTexts(root) == List("a", "b", "c"))
      },
      test("removing items removes their nodes AND cancels their per-item cleanup (leak check)") {
        for
          inner <- sq("hi")
          items <- sq(Seq("a", "b", "c"))
          ui: UI[Any] = UI.Element(
            "ul",
            Vector.empty,
            Vector(UI.ForEach(items, identity, _ => UI.Element("li", Vector.empty, Vector(UI.ReactiveText(inner))))),
          )
          (_, _) <- mountInto(ui)
          n0     <- inner.observerCount // 3, one per row
          _      <- items.set(Seq("a"))
          n1     <- inner.observerCount // 1, two rows' subscriptions cancelled
        yield assertTrue(n0 == 3, n1 == 1)
      },
      test("reordering REUSES the same DOM nodes (identity), only moving them") {
        for
          items     <- sq(Seq("a", "b", "c"))
          (root, _) <- mountInto(feUi(items))
          before = visibleNodes(root)
          _ <- items.set(Seq("c", "b", "a"))
          after = visibleNodes(root)
        yield assertTrue(
          before.size == 3,
          after.size == 3,
          after(0).asInstanceOf[AnyRef] eq before(2).asInstanceOf[AnyRef],
          after(1).asInstanceOf[AnyRef] eq before(1).asInstanceOf[AnyRef],
          after(2).asInstanceOf[AnyRef] eq before(0).asInstanceOf[AnyRef],
          visibleTexts(root) == List("c", "b", "a"),
        )
      },
      test("clearing the list removes every node") {
        for
          items     <- sq(Seq("a", "b"))
          (root, _) <- mountInto(feUi(items))
          _         <- items.set(Seq.empty)
        yield assertTrue(visibleTexts(root) == Nil)
      },
      test("duplicate keys: first occurrence wins (defined ascent policy)") {
        for
          items     <- sq(Seq("a", "a", "b"))
          (root, _) <- mountInto(feUi(items))
        yield assertTrue(visibleTexts(root) == List("a", "b"))
      },
    ),
    suite("lifecycle")(
      test("OnMount fires after insertion, with the element already attached to its parent") {
        for
          hasParent <- Ref.make(false)
          hook: (Any => UIO[Unit]) = (el: Any) => hasParent.set(el.asInstanceOf[Node].parentNode != null)
          ui: UI[Any]              = UI.Element("div", Vector(Attr.OnMount(hook)), Vector.empty)
          _     <- mountInto(ui)
          fired <- hasParent.get
        yield assertTrue(fired)
      },
      test("two OnMount hooks fire in source order") {
        for
          order <- Ref.make(Vector.empty[Int])
          h1: (Any => UIO[Unit]) = (_: Any) => order.update(_ :+ 1)
          h2: (Any => UIO[Unit]) = (_: Any) => order.update(_ :+ 2)
          ui: UI[Any]            = UI.Element("div", Vector(Attr.OnMount(h1), Attr.OnMount(h2)), Vector.empty)
          _    <- mountInto(ui)
          seen <- order.get
        yield assertTrue(seen == Vector(1, 2))
      },
      test("a failing OnMount hook does not prevent its sibling from firing") {
        for
          fired <- Ref.make(false)
          bad: (Any => UIO[Unit])  = (_: Any) => ZIO.die(new RuntimeException("boom"))
          good: (Any => UIO[Unit]) = (_: Any) => fired.set(true)
          ui: UI[Any]              = UI.Element("div", Vector(Attr.OnMount(bad), Attr.OnMount(good)), Vector.empty)
          _  <- mountInto(ui)
          ok <- fired.get
        yield assertTrue(ok)
      },
      test("OnUnmount fires when the enclosing cleanup runs") {
        for
          torn <- Ref.make(false)
          hook: (Any => UIO[Unit]) = (_: Any) => torn.set(true)
          ui: UI[Any]              = UI.Element("div", Vector(Attr.OnUnmount(hook)), Vector.empty)
          (_, cleanup) <- mountInto(ui)
          before       <- torn.get
          _            <- cleanup.cancelAll
          after        <- torn.get
        yield assertTrue(!before, after)
      },
    ),
    suite("events")(
      test("an EventHandler runs the handler when the event is dispatched on the element") {
        for
          fired <- Ref.make(0)
          handler     = (_: AscentEvent) => fired.update(_ + 1)
          ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
          (root, _) <- mountInto(ui)
          btn = firstElement(root)
          _ <- ZIO.succeed(btn.dispatchEvent(mkEvent("click", bubbles = false)))
          n <- fired.get
        yield assertTrue(n == 1)
      },
      test("cleanup detaches the listener — a later dispatch does nothing") {
        for
          fired <- Ref.make(0)
          handler     = (_: AscentEvent) => fired.update(_ + 1)
          ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
          (root, clean) <- mountInto(ui)
          btn = firstElement(root)
          _ <- ZIO.succeed(btn.dispatchEvent(mkEvent("click", bubbles = false)))
          _ <- clean.cancelAll
          _ <- ZIO.succeed(btn.dispatchEvent(mkEvent("click", bubbles = false)))
          n <- fired.get
        yield assertTrue(n == 1)
      },
      test("a bubbling event dispatched on a CHILD reaches an ancestor's handler (real bubble phase)") {
        for
          outerFired <- Ref.make(0)
          handler     = (_: AscentEvent) => outerFired.update(_ + 1)
          ui: UI[Any] = UI.Element(
            "div",
            Vector(Attr.EventHandler("click", handler)),
            Vector(UI.Element("button", Vector.empty, Vector(UI.Text("x")))),
          )
          (root, _) <- mountInto(ui)
          outer = firstElement(root)
          inner = childList(outer).head.asInstanceOf[Element]
          _ <- ZIO.succeed(inner.dispatchEvent(mkEvent("click", bubbles = true)))
          n <- outerFired.get
        yield assertTrue(n == 1)
      },
    ),
  )

  private def mkEvent(tpe: String, bubbles: Boolean): ascent.domcore.generated.Event =
    val e = ascent.domcore.generated.EventMemory()
    e.initEvent(tpe, bubbles, false)
    e
end MountInMemorySpec
