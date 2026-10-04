package ascent.dsl

import ascent.ast.{Attr, UI}
import ascent.domtypes.{AttrKey, AttrValue, Codec, ElementKey, VoidElementKey}
import ascent.squawk.sq
import zio.test.*

object DslSpec extends ZIOSpecDefault:

  // Inline keys so this spec doesn't depend on the generated Elements/Attrs objects.
  private val divKey  = ElementKey[ascent.domtypes.tags.Element]("div")
  private val spanKey = ElementKey[ascent.domtypes.tags.Element]("span")
  private val brKey   = VoidElementKey[ascent.domtypes.tags.Element]("br")
  private val idKey   = AttrKey[String, ascent.domtypes.tags.Element]("id", Codec.StringAsIs)
  private val reqKey  = AttrKey[Boolean, ascent.domtypes.tags.Element]("required", Codec.BooleanAsAttrPresence)

  def spec = suite("DSL")(
    suite("Element constructor")(
      test("a String arg is lifted to a Text child") {
        val ui = divKey("hello")
        assertTrue(ui == UI.Element("div", Vector.empty, Vector(UI.Text("hello"))))
      },
      test("an Attr arg lands in attrs, a UI arg lands in children, in source order") {
        val ui = divKey(idKey("x"), spanKey("hi"))
        assertTrue(
          ui == UI.Element(
            tag = "div",
            attrs = Vector(Attr.StaticAttr("id", AttrValue.Str("x"))),
            children = Vector(UI.Element("span", Vector.empty, Vector(UI.Text("hi")))),
          )
        )
      },
      test("a Squawk[String] is lifted to a ReactiveText child") {
        for s <- sq("v")
        yield
          val ui = spanKey(s)
          assertTrue(
            ui.asInstanceOf[UI.Element[Any]].children == Vector(UI.ReactiveText(s))
          )
      },
      test("a Squawk[UI] is lifted to a ReactiveChild") {
        for s <- sq[UI[Any]](UI.Text("a"))
        yield
          val ui = divKey(s)
          assertTrue(
            ui.asInstanceOf[UI.Element[Any]].children == Vector(UI.ReactiveChild(s))
          )
      },
      test("a Seq[Arg] flattens into the args list") {
        val children: Seq[Arg[Any, ascent.domtypes.tags.Element]] = Seq(spanKey("a"), spanKey("b"))
        val ui                                                    = divKey(children)
        assertTrue(ui.asInstanceOf[UI.Element[Any]].children.size == 2)
      },
      test("Option[Arg] - Some lifts, None becomes Empty") {
        // Widened to Option[Arg] so the Option-to-Arg conversion is the only viable path (not the bare Some case).
        val present: Option[Arg[Any, ascent.domtypes.tags.Element]] = Some(spanKey("a"))
        val absent: Option[Arg[Any, ascent.domtypes.tags.Element]]  = None
        val ui                                                      = divKey(present, absent)
        assertTrue(ui.asInstanceOf[UI.Element[Any]].children.size == 1)
      },
      test("a void element accepts attributes but its constructor takes no children") {
        val empty    = brKey()
        val withAttr = brKey(idKey("x"))
        assertTrue(
          empty == UI.Element("br", Vector.empty, Vector.empty),
          withAttr == UI.Element("br", Vector(Attr.StaticAttr("id", AttrValue.Str("x"))), Vector.empty),
        )
      },
      test("a void element REJECTS a child at compile time") {
        assertZIO(typeCheck("""brKey("text child")"""))(Assertion.isLeft) &&
        assertZIO(typeCheck("""brKey(spanKey("kid"))"""))(Assertion.isLeft)
      },
      test("a non-void element still accepts children (control for the rejection test)") {
        assertZIO(typeCheck("""divKey("text child")"""))(Assertion.isRight)
      },
    ),
    suite("Attribute lift via AttrKey")(
      test("AttrKey[V](v) yields a StaticAttr through the codec") {
        assertTrue(idKey("x") == Arg.TypedAttrArg(Attr.StaticAttr("id", AttrValue.Str("x"))))
      },
      test("AttrKey[Boolean](false) for presence-coded attrs yields Absent") {
        assertTrue(reqKey(false) == Arg.TypedAttrArg(Attr.StaticAttr("required", AttrValue.Absent)))
      },
      test("AttrKey[V](Squawk[V]) yields a ReactiveAttr") {
        for s <- sq("y")
        yield idKey(s) match
          case Arg.TypedAttrArg(attr) => assertTrue(attr.isInstanceOf[Attr.ReactiveAttr])
          case _                      => assertTrue(false)
      },
    ),
    suite("Control-flow helpers")(
      test("static when(true) returns the body, when(false) returns Empty") {
        assertTrue(
          when(true)(UI.Text("yes")) == UI.Text("yes"),
          when(false)(UI.Text("yes")) == UI.Empty,
        )
      },
      test("reactive when(squawk) builds a UI.When carrying the cond and a body thunk") {
        for s <- sq(true)
        yield
          var built = 0
          val ui    = when(s) { built += 1; UI.Text("body") }
          assertTrue(built == 0, ui.isInstanceOf[UI.When[?]])
      },
      test("forEach builds a UI.ForEach with the items, key, and render fn") {
        for s <- sq(Seq(1, 2))
        yield
          val ui = forEach(s)(_.toString)(i => spanKey(i.toString))
          assertTrue(ui.isInstanceOf[UI.ForEach[?, ?]])
      },
    ),
    suite("Counter ergonomic from the plan")(
      test("the counter component reads naturally and produces the expected AST shape") {
        for
          count <- sq(0)
          ui = (divKey(
            spanKey(count.map(_.toString))
          ): UI[Any])
          span = ui.asInstanceOf[UI.Element[Any]].children.head.asInstanceOf[UI.Element[Any]]
          rt   = span.children.head.asInstanceOf[UI.ReactiveText]
          now <- rt.src.get
        yield assertTrue(now == "0")
      }
    ),
  )
end DslSpec
