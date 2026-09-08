package ascent.squawk

import zio.test.*

/** Locks [[Eq]] laws and resolution: structural derivation, the `CanEqual` fallback, explicit givens, constructors. */
object EqResolutionSpec extends ZIOSpecDefault:

  final case class Point(x: Int, y: Int)
  final case class Line(a: Point, b: Point)

  enum OpenMenu derives Eq:
    case Mode, Settings, Model, Effort

  enum DiffLine derives Eq:
    case Context(text: String)
    case Add(text: String)
    case Del(text: String)

  enum Tree derives Eq:
    case Leaf(n: Int)
    case Branch(left: Tree, right: Tree)

  final case class Tagged(v: Int)
  given explicitTagged: Eq[Tagged] = (_, _) => true

  final case class Wrapper(t: Tagged)

  val genOpenMenu: Gen[Any, OpenMenu] = Gen.fromIterable(OpenMenu.values)
  val genPoint: Gen[Any, Point]       = Gen.int.zipWith(Gen.int)(Point.apply)
  val genDiffLine: Gen[Any, DiffLine] =
    Gen.string.flatMap: s =>
      Gen.elements(DiffLine.Context(s), DiffLine.Add(s), DiffLine.Del(s))
  val genLeaf: Gen[Any, Tree] = Gen.int(-5, 5).map(Tree.Leaf.apply)
  val genTree: Gen[Any, Tree] =
    Gen.oneOf(genLeaf, genLeaf.zipWith(genLeaf)(Tree.Branch.apply))

  def agreesWithEquals[A](using eq: Eq[A]): (A, A) => TestResult =
    (a, b) => assertTrue(eq.eqv(a, b) == (a == b))

  def spec = suite("Eq resolution")(
    suite("laws on a derived enum")(
      test("reflexive") {
        checkAll(genOpenMenu) { a =>
          assertTrue(summon[Eq[OpenMenu]].eqv(a, a))
        }
      },
      test("symmetric") {
        checkAll(genOpenMenu, genOpenMenu) { (a, b) =>
          val eq = summon[Eq[OpenMenu]]
          assertTrue(eq.eqv(a, b) == eq.eqv(b, a))
        }
      },
      test("transitive") {
        checkAll(genOpenMenu, genOpenMenu, genOpenMenu) { (a, b, c) =>
          val eq = summon[Eq[OpenMenu]]
          assertTrue(!(eq.eqv(a, b) && eq.eqv(b, c)) || eq.eqv(a, c))
        }
      },
    ),
    suite("structural derivation agrees with ==")(
      test("parameterless enum") {
        checkAll(genOpenMenu, genOpenMenu)(agreesWithEquals)
      },
      test("payload enum") {
        check(genDiffLine, genDiffLine)(agreesWithEquals)
      },
      test("case class") {
        check(genPoint, genPoint)(agreesWithEquals)
      },
      test("nested case class") {
        val genLine = genPoint.zipWith(genPoint)(Line.apply)
        check(genLine, genLine)(agreesWithEquals)
      },
      test("Option of a derived enum") {
        checkAll(Gen.option(genOpenMenu), Gen.option(genOpenMenu))(agreesWithEquals)
      },
      test("recursive sum") {
        check(genTree, genTree)(agreesWithEquals)
      },
    ),
    suite("fallback + priority")(
      test("a primitive resolves the universal fallback") {
        check(Gen.string, Gen.string)(agreesWithEquals)
      },
      test("an explicit given WINS over structural derivation") {
        val eq = summon[Eq[Tagged]]
        assertTrue(eq.eqv(Tagged(1), Tagged(2)))
      },
      test("derivation uses a field's explicit Eq") {
        val eq = summon[Eq[Wrapper]]
        assertTrue(eq.eqv(Wrapper(Tagged(1)), Wrapper(Tagged(2))))
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
        val res = typeCheck("""summon[ascent.squawk.Eq[Int => Int]]""")
        assertZIO(res)(Assertion.isRight)
      },
    ),
  )
end EqResolutionSpec
