package ascent.ast

import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

object AstIdSpec extends ZIOSpecDefault:

  def spec = suite("AstId")(
    suite("structural id is a pure function of the AST")(
      test("two structurally equal Elements get the same id") {
        val a = UI.Element("div", Vector.empty, Vector(UI.Text("hi")))
        val b = UI.Element("div", Vector.empty, Vector(UI.Text("hi")))
        assertTrue(AstId.compute(a) == AstId.compute(b))
      },
      test("changing the tag changes the id") {
        val a = UI.Element("div", Vector.empty, Vector.empty)
        val b = UI.Element("span", Vector.empty, Vector.empty)
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
      test("changing children changes the id") {
        val a = UI.Element("div", Vector.empty, Vector(UI.Text("a")))
        val b = UI.Element("div", Vector.empty, Vector(UI.Text("b")))
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
      test("reordering children changes the id") {
        val a = UI.Element("div", Vector.empty, Vector(UI.Text("x"), UI.Text("y")))
        val b = UI.Element("div", Vector.empty, Vector(UI.Text("y"), UI.Text("x")))
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
    ),
    suite("attributes: names contribute, values do not")(
      test("adding an attribute changes the id") {
        import ascent.ast.Attr.StaticAttr
        val a = UI.Element("button", Vector.empty, Vector.empty)
        val b = UI.Element("button", Vector(StaticAttr("class", AttrValue.Str("primary"))), Vector.empty)
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
      test("changing an attribute's VALUE does NOT change the id (state, not structure)") {
        import ascent.ast.Attr.StaticAttr
        val a = UI.Element("button", Vector(StaticAttr("class", AttrValue.Str("primary"))), Vector.empty)
        val b = UI.Element("button", Vector(StaticAttr("class", AttrValue.Str("secondary"))), Vector.empty)
        assertTrue(AstId.compute(a) == AstId.compute(b))
      },
      test("changing an attribute's NAME (with the same value) DOES change the id") {
        import ascent.ast.Attr.StaticAttr
        val a = UI.Element("button", Vector(StaticAttr("id", AttrValue.Str("x"))), Vector.empty)
        val b = UI.Element("button", Vector(StaticAttr("name", AttrValue.Str("x"))), Vector.empty)
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
    ),
    suite("reactive bindings: structure matters, identity/value does not")(
      test("ReactiveText at the same position with two different Squawks yields the same id") {
        for
          s1 <- sq("hello")
          s2 <- sq("world")
        yield
          val a = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(s1)))
          val b = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(s2)))
          assertTrue(AstId.compute(a) == AstId.compute(b))
      },
      test("Replacing a ReactiveText with a static Text DOES change the id (different shape)") {
        for s <- sq("hello")
        yield
          val a = UI.Element("span", Vector.empty, Vector(UI.ReactiveText(s)))
          val b = UI.Element("span", Vector.empty, Vector(UI.Text("hello")))
          assertTrue(AstId.compute(a) != AstId.compute(b))
      },
    ),
    suite("Empty / Fragment / event handlers")(
      test("Empty has a fixed id") {
        assertTrue(AstId.compute(UI.Empty) == AstId.compute(UI.Empty))
      },
      test("Empty's id differs from Text(\"\") and from Element(_)") {
        assertTrue(
          AstId.compute(UI.Empty) != AstId.compute(UI.Text("")),
          AstId.compute(UI.Empty) != AstId.compute(UI.Element("div", Vector.empty, Vector.empty)),
        )
      },
      test("Fragments hash position-sensitively over their children") {
        val a = UI.Fragment(Vector(UI.Text("a"), UI.Text("b")))
        val b = UI.Fragment(Vector(UI.Text("b"), UI.Text("a")))
        assertTrue(AstId.compute(a) != AstId.compute(b))
      },
      test("an EventHandler attribute contributes structure, not its handler value") {
        import ascent.ast.Attr.EventHandler
        val h1: AscentEvent => UIO[Unit] = _ => ZIO.unit
        val h2: AscentEvent => UIO[Unit] = _ => ZIO.unit
        val a                            = UI.Element("button", Vector(EventHandler("click", h1)), Vector.empty)
        val b                            = UI.Element("button", Vector(EventHandler("click", h2)), Vector.empty)
        assertTrue(AstId.compute(a) == AstId.compute(b))
      },
      test("every reactive-boundary variant has a distinct, stable id (every UI case is handled)") {
        // A UI variant `compute` forgets to handle would MatchError once a parent hashes its children.
        for s <- sq(Seq(1))
        yield
          val reactiveText  = AstId.compute(UI.ReactiveText(s.map(_.toString)))
          val reactiveChild = AstId.compute(UI.ReactiveChild(s.map(_ => UI.Empty)))
          val foreach       = AstId.compute(UI.ForEach(s, _.toString, _ => UI.Empty))
          val foreachSignal = AstId.compute(UI.ForEachSignal(s, _.toString, (_, _, _) => UI.Empty))
          val ids           = List(reactiveText, reactiveChild, foreach, foreachSignal)
          assertTrue(
            ids.distinct.size == ids.size,
            AstId.compute(UI.ForEachSignal(s, _.toString, (_, _, _) => UI.Empty)) == foreachSignal,
          )
      },
    ),
    suite("IdAssigner — registry-backed uniqueness (default mode)")(
      test("the same AST value gets the same id from one assigner") {
        val tree = UI.Element("div", Vector.empty, Vector(UI.Text("hi")))
        for
          assigner <- IdAssigner.make(IdMode.HashWithRegistry)
          id1      <- assigner.assign(tree)
          id2      <- assigner.assign(tree)
        yield assertTrue(id1 == id2)
      },
      test("two structurally distinct ASTs that happen to hash-collide get DIFFERENT ids") {
        // A constant hash forces the collision a real MurmurHash won't reproduce on demand.
        val a                           = UI.Text("alpha")
        val b                           = UI.Text("beta")
        val constantHash: UI[?] => Long = _ => 42L
        for
          assigner <- IdAssigner.makeWith(IdMode.HashWithRegistry, constantHash)
          ida      <- assigner.assign(a)
          idb      <- assigner.assign(b)
        yield assertTrue(ida != idb)
      },
      test("after a tiebreaker, the same colliding AST still gets its OWN stable id") {
        val a                           = UI.Text("alpha")
        val b                           = UI.Text("beta")
        val constantHash: UI[?] => Long = _ => 42L
        for
          assigner <- IdAssigner.makeWith(IdMode.HashWithRegistry, constantHash)
          ida1     <- assigner.assign(a)
          idb1     <- assigner.assign(b)
          ida2     <- assigner.assign(a)
          idb2     <- assigner.assign(b)
        yield assertTrue(ida1 == ida2, idb1 == idb2, ida1 != idb1)
      },
    ),
    suite("IdAssigner — Hash mode (no registry)")(
      test("two structurally distinct ASTs that hash-collide ARE allowed to share an id") {
        val a                           = UI.Text("alpha")
        val b                           = UI.Text("beta")
        val constantHash: UI[?] => Long = _ => 42L
        for
          assigner <- IdAssigner.makeWith(IdMode.Hash, constantHash)
          ida      <- assigner.assign(a)
          idb      <- assigner.assign(b)
        yield assertTrue(ida == idb)
      }
    ),
    suite("cross-platform golden values")(
      // Pinned outputs of the stdlib MurmurHash3 path. If JVM/JS/Native ever diverge on the hash, the
      // differing platform fails HERE — the only test that would catch it (the others are self-relative).
      test("known trees hash to their pinned 64-bit ids on every platform") {
        import ascent.ast.Attr.StaticAttr
        val text   = UI.Text("hello")
        val div    = UI.Element("div", Vector.empty, Vector(UI.Text("hi")))
        val button = UI.Element("button", Vector(StaticAttr("class", AttrValue.Str("x"))), Vector(UI.Text("Go")))
        assertTrue(
          AstId.compute(text) == -950922095406265034L,
          AstId.compute(div) == -1621787885789732505L,
          AstId.compute(button) == -1386254446710026729L,
          AstId.compute(UI.Empty) == 3L,
        )
      }
    ),
    suite("renderAttr")(
      test("rendered attribute string is non-empty alphanumeric (lowercase base36)") {
        val id       = AstId.compute(UI.Element("div", Vector.empty, Vector.empty))
        val rendered = AstId.renderAttr(id)
        assertTrue(rendered.nonEmpty, rendered.matches("[0-9a-z]+"))
      }
    ),
  )
end AstIdSpec
