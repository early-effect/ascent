package ascent.squawk

import zio.test.*

/** Locks the resolution and priority of [[Eq]]: structural derivation for product/sum types, the `CanEqual` fallback
  * for the rest, explicit givens winning over both, and strictEquality gating the fallback shut.
  */
object EqResolutionSpec extends ZIOSpecDefault:

  final case class Point(x: Int, y: Int)

  enum Color:
    case Red, Green, Blue

  // Explicit instance disagreeing with structural equality (reports all-equal) so priority is observable.
  final case class Tagged(v: Int)
  given explicitTagged: Eq[Tagged] = (_, _) => true

  def spec = suite("Eq resolution")(
    suite("structural derivation (Mirror)")(
      test("a case class resolves a structural Eq with no explicit instance") {
        val eq = summon[Eq[Point]]
        assertTrue(
          eq.eqv(Point(1, 2), Point(1, 2)),
          !eq.eqv(Point(1, 2), Point(1, 3)),
        )
      },
      test("an enum resolves a structural Eq") {
        val eq = summon[Eq[Color]]
        assertTrue(
          eq.eqv(Color.Red, Color.Red),
          !eq.eqv(Color.Red, Color.Blue),
        )
      },
      test("derivation recurses through nested case classes") {
        final case class Line(a: Point, b: Point)
        val eq = summon[Eq[Line]]
        assertTrue(
          eq.eqv(Line(Point(0, 0), Point(1, 1)), Line(Point(0, 0), Point(1, 1))),
          !eq.eqv(Line(Point(0, 0), Point(1, 1)), Line(Point(0, 0), Point(2, 1))),
        )
      },
    ),
    suite("fallback + priority")(
      test("a primitive resolves the universal fallback") {
        val eq = summon[Eq[String]]
        assertTrue(eq.eqv("a", "a"), !eq.eqv("a", "b"))
      },
      test("an explicit given WINS over structural derivation") {
        val eq = summon[Eq[Tagged]]
        assertTrue(eq.eqv(Tagged(1), Tagged(2)))
      },
    ),
    suite("explicit constructors")(
      test("byRef compares by identity: equal contents but distinct instances are NOT equal") {
        val a  = Array(1, 2)
        val b  = Array(1, 2)
        val eq = Eq.byRef[Array[Int]]
        assertTrue(eq.eqv(a, a), !eq.eqv(a, b))
      },
      test("by compares on a derived key") {
        final case class Doc(version: Int, body: String)
        val eq = Eq.by((_: Doc).version)
        assertTrue(
          eq.eqv(Doc(1, "x"), Doc(1, "totally different body")),
          !eq.eqv(Doc(1, "x"), Doc(2, "x")),
        )
      },
    ),
    suite("safety: incomparable types")(
      test("under strictEquality, Eq for a raw function type does NOT resolve") {
        val res = typeCheck(
          """import scala.language.strictEquality
             summon[ascent.squawk.Eq[Int => Int]]"""
        )
        assertZIO(res)(Assertion.isLeft)
      },
      test("with strictEquality OFF, the universal fallback still applies (documents the limitation)") {
        // No strictEquality import: canEqualAny is synthesised, so the fallback resolves even for a function type.
        val res = typeCheck("""summon[ascent.squawk.Eq[Int => Int]]""")
        assertZIO(res)(Assertion.isRight)
      },
    ),
  )
end EqResolutionSpec
