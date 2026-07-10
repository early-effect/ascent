package ascent

// Deliberately no `import ascent.ast.*` / `ascent.squawk.*`: every name below must arrive via the
// open-package facade (core/.../ascent/coreExports.scala) — that union is what's under test.
import ascent.*
import zio.*
import zio.test.*

object FacadeSpec extends ZIOSpecDefault:

  val empty: UI[Any]  = UI.Empty
  val attr: Attr[Any] = Attr.StaticAttr("id", AttrValue.Str("x"))
  val ev: AscentEvent = AscentEvent.simple(targetValue = Some("v"))

  def makeSource: UIO[Source[Int]] = sq(0)
  val const: Squawk[Int]           = Squawk.const(1)

  def spec = suite("ascent.* facade (core)")(
    test("AST + AscentEvent reachable through the facade") {
      assertTrue(
        empty == UI.Empty,
        attr == Attr.StaticAttr("id", AttrValue.Str("x")),
        ev.targetValue.contains("v"),
      )
    },
    test("Squawk / Eq / sq reachable; sq allocates a live Source") {
      for
        src <- sq(41)
        _   <- src.set(42)
        got <- src.get
      yield assertTrue(got == 42, const != null)
    },
    test("the dom-types facade unions in through the same wildcard (open package across jars)") {
      assertTrue(Elements.div.domName == "div", Attrs.className.domName == "class")
    },
  )
end FacadeSpec
