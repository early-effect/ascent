package ascent.conduit

import _root_.conduit.*
import zio.*
import zio.test.*

/** Bridge between conduit's lens-keyed listener model and ascent's [[ascent.squawk.Squawk]]: `c.squawk(lens)` returns a
  * `Squawk[S]` mirroring a model slice, updated via dispatch → handler → conduit FastEq dedup → bridge → Squawk Eq
  * dedup.
  */
object ConduitBridgeSpec extends ZIOSpecDefault:

  case class Model(counter: Int, label: String) derives Optics

  enum Op extends Action:
    case IncCounter(by: Int)
    case SetLabel(text: String)

  val M = Optics[Model]

  val handler: ActionHandler[Model, Model, Nothing] =
    handle[Model, Model, Nothing](M):
      case Op.IncCounter(by) => focus(_.counter)(update(_ + by))
      case Op.SetLabel(t)    => focus(_.label)(updated(t))

  private val conduit: UIO[Conduit[Model, Nothing]] =
    Conduit(Model(counter = 0, label = "alpha"))(handler)

  def spec = suite("ascent-conduit bridge")(
    test("c.squawk(lens) seeds the Squawk with the current model slice") {
      for
        c <- conduit
        s <- c.squawk(M(_.counter))
        v <- s.get
      yield assertTrue(v == 0)
    },
    test("dispatching an action updates the bridged Squawk after run") {
      for
        c    <- conduit
        s    <- c.squawk(M(_.counter))
        _    <- c(Op.IncCounter(3))
        _    <- c.run()
        next <- s.get
      yield assertTrue(next == 3)
    },
    test("only the lens slice triggers Squawk updates: changes to other slices don't fire") {
      for
        c     <- conduit
        s     <- c.squawk(M(_.counter))
        fires <- Ref.make(0)
        _     <- s.observe(_ => fires.update(_ + 1))
        _     <- c(Op.SetLabel("beta"), Op.SetLabel("gamma"))
        _     <- c.run()
        n     <- fires.get
      yield assertTrue(n == 0)
    },
    test("setting the same value does not fire (conduit FastEq and Squawk Eq dedup compose)") {
      for
        c     <- conduit
        s     <- c.squawk(M(_.counter))
        fires <- Ref.make(0)
        _     <- s.observe(_ => fires.update(_ + 1))
        _     <- c(Op.IncCounter(0)) // adds 0, model unchanged
        _     <- c.run()
        n     <- fires.get
      yield assertTrue(n == 0)
    },
    test("multiple distinct dispatches each fire the observer once, in order") {
      for
        c    <- conduit
        s    <- c.squawk(M(_.counter))
        seen <- Ref.make[List[Int]](Nil)
        _    <- s.observe(v => seen.update(v :: _))
        _    <- c(Op.IncCounter(1), Op.IncCounter(1), Op.IncCounter(2))
        _    <- c.run()
        xs   <- seen.get
      yield assertTrue(xs == List(4, 2, 1)) // prepended, so newest first
    },
  )
end ConduitBridgeSpec
