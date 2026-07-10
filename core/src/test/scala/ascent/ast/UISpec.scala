package ascent.ast

import ascent.domtypes.{AttrKey, AttrValue, Codec}
import ascent.squawk.{Squawk, sq}
import zio.*
import zio.test.*

object UISpec extends ZIOSpecDefault:

  def spec = suite("UI AST")(
    suite("Static nodes")(
      test("Element carries tag, attrs, and children as immutable Vectors") {
        val el = UI.Element(
          tag = "div",
          attrs = Vector(Attr.StaticAttr("id", AttrValue.Str("x"))),
          children = Vector(UI.Text("hello")),
        )
        assertTrue(
          el.tag == "div",
          el.attrs.size == 1,
          el.children == Vector(UI.Text("hello")),
        )
      },
      test("Text wraps a string; Empty is a singleton; Fragment groups children") {
        assertTrue(
          UI.Text("hi") == UI.Text("hi"),
          (UI.Empty: UI[Any]) eq UI.Empty,
          UI.Fragment(Vector(UI.Text("a"), UI.Text("b"))).children.size == 2,
        )
      },
    ),
    suite("Reactive boundaries")(
      test("ReactiveText carries a Squawk[String] - the binding engine subscribes for textNode.data") {
        for s <- sq("init")
        yield
          val node: UI[Any] = UI.ReactiveText(s)
          assertTrue(node.isInstanceOf[UI.ReactiveText])
      },
      test("ReactiveChild carries a Squawk[UI] - the binding engine swaps subtrees on change") {
        for s <- sq[UI[Any]](UI.Text("a"))
        yield assertTrue(UI.ReactiveChild(s).isInstanceOf[UI.ReactiveChild[?]])
      },
      test("When carries a Squawk[Boolean] cond and a thunk so the body is built lazily") {
        for s <- sq(false)
        yield
          var built = 0
          val w     = UI.When(
            s,
            () =>
              built += 1; UI.Text("body"),
          )
          assertTrue(built == 0, w.cond eq s)
      },
      test("ForEach captures items, key fn, and render fn for keyed reconciliation") {
        for s <- sq(Seq(1, 2, 3))
        yield
          val fe = UI.ForEach(s, _.toString, i => UI.Text(i.toString))
          assertTrue(fe.items eq s, fe.key(7) == "7", fe.render(9) == UI.Text("9"))
      },
    ),
    suite("Attributes")(
      test("StaticAttr carries name + already-encoded AttrValue") {
        val a = Attr.StaticAttr("class", AttrValue.Str("foo"))
        assertTrue(a.name == "class", a.value == AttrValue.Str("foo"))
      },
      test("ReactiveAttr carries a Squawk[AttrValue] for live binding") {
        for s <- sq[AttrValue](AttrValue.Str("foo"))
        yield assertTrue(Attr.ReactiveAttr("class", s).name == "class")
      },
      test("AttrKey[V] applied to a value produces a StaticAttr via its Codec") {
        val idKey       = AttrKey[String]("id", Codec.StringAsIs)
        val requiredKey = AttrKey[Boolean]("required", Codec.BooleanAsAttrPresence)
        assertTrue(
          Attr.from(idKey, "x") == Attr.StaticAttr("id", AttrValue.Str("x")),
          Attr.from(requiredKey, true) == Attr.StaticAttr("required", AttrValue.Str("")),
          Attr.from(requiredKey, false) == Attr.StaticAttr("required", AttrValue.Absent),
        )
      },
      test("AttrKey[V] applied to a Squawk[V] produces a ReactiveAttr that maps the codec on each emit") {
        for
          s <- sq("a")
          key  = AttrKey[String]("id", Codec.StringAsIs)
          attr = Attr.fromSquawk(key, s)
          init <- attr.value.get
          _    <- s.set("b")
          next <- attr.value.get
        yield assertTrue(init == AttrValue.Str("a"), next == AttrValue.Str("b"))
      },
    ),
    suite("AscentEvent contract")(
      test("targetValue surfaces the input's current value, or None for non-form events") {
        val withValue    = AscentEvent.simple(targetValue = Some("typed"))
        val withoutValue = AscentEvent.simple(targetValue = None)
        assertTrue(
          withValue.targetValue == Some("typed"),
          withoutValue.targetValue == None,
        )
      },
      test("key surfaces the keyboard key for keyboard events, None otherwise") {
        val k = AscentEvent.simple(key = Some("Enter"))
        assertTrue(k.key == Some("Enter"), AscentEvent.simple().key == None)
      },
      test("preventDefault is a UIO[Unit] (effectful, observable, composable with retry/etc.)") {
        for
          called <- Ref.make(false)
          ev = AscentEvent.simple(preventDefaultEffect = called.set(true))
          _ <- ev.preventDefault
          v <- called.get
        yield assertTrue(v)
      },
      test("preventDefaultNow is EAGER (plain Unit) so it runs on the dispatch stack, in the handler body") {
        // Drag-and-drop and form/link interception need preventDefault to fire synchronously during dispatch;
        // the UIO form runs only when its effect later executes.
        var prevented = false
        val ev        = AscentEvent.simple(onPreventDefaultNow = () => prevented = true)
        ev.preventDefaultNow()
        assertTrue(prevented)
      },
      test("stopPropagationNow is EAGER (plain Unit), like preventDefaultNow") {
        var stopped = false
        val ev      = AscentEvent.simple(onStopPropagationNow = () => stopped = true)
        ev.stopPropagationNow()
        assertTrue(stopped)
      },
      test("eager control methods default to harmless no-ops on a bare simple() event") {
        val ev = AscentEvent.simple()
        ev.preventDefaultNow()
        ev.stopPropagationNow()
        assertCompletes
      },
    ),
    suite("EventHandler attr")(
      test("EventHandler is an Attr that pairs an event name with an AscentEvent => UIO[Unit]") {
        for fired <- Ref.make(0)
        yield
          val h: AscentEvent => UIO[Unit] = _ => fired.update(_ + 1)
          val attr                        = Attr.EventHandler("click", h)
          assertTrue(attr.event == "click", (attr.handler eq h))
      }
    ),
  )
end UISpec
