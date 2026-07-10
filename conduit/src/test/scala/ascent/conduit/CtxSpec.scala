package ascent.conduit

import ascent.squawk.*
import zio.*
import zio.test.*

/** Spec for the conduit-free view handle [[Ctx]]. This unit imports no `conduit.` / `_root_.conduit.` and drives
  * everything through `c.ctx`, so it stops compiling if `Ctx`/`.ctx`/path-based `squawk` ever regress its conduit-free
  * guarantee.
  */
object CtxSpec extends ZIOSpecDefault:

  import CtxModel.{Op, Model, Item}

  def spec = suite("ascent-conduit Ctx handle")(
    test("ctx.squawk(_.path) seeds with the current model slice") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s <- ctx.squawk(_.counter)
        v <- s.get
      yield assertTrue(v == 0)
    },
    test("dispatching via ctx(action) updates the bridged squawk after run") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s    <- ctx.squawk(_.counter)
        _    <- ctx(Op.IncCounter(3))
        _    <- c.run()
        next <- s.get
      yield assertTrue(next == 3)
    },
    test("path-scoping: changing another slice does not fire this squawk's observer") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s     <- ctx.squawk(_.counter)
        fires <- Ref.make(0)
        _     <- s.observe(_ => fires.update(_ + 1))
        _     <- ctx(Op.SetLabel("beta"), Op.SetLabel("gamma"))
        _     <- c.run()
        n     <- fires.get
      yield assertTrue(n == 0)
    },
    test("dedup: an action that yields an unchanged model fires the observer zero times") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s     <- ctx.squawk(_.counter)
        fires <- Ref.make(0)
        _     <- s.observe(_ => fires.update(_ + 1))
        _     <- ctx(Op.IncCounter(0)) // adds 0, model unchanged
        _     <- c.run()
        n     <- fires.get
      yield assertTrue(n == 0)
    },
    test("ctx.model seeds with and tracks the whole model") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s    <- ctx.model
        seed <- s.get
        _    <- ctx(Op.IncCounter(2), Op.SetLabel("beta"))
        _    <- c.run()
        next <- s.get
      yield assertTrue(
        seed.counter == 0 && seed.label == "alpha",
        next.counter == 2 && next.label == "beta",
      )
    },
    test("ctx.squawk(_.meta.title) reaches a nested field and tracks it") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        s    <- ctx.squawk(_.meta.title)
        seed <- s.get
        _    <- ctx(Op.SetTitle("hello"))
        _    <- c.run()
        next <- s.get
      yield assertTrue(seed == "untitled", next == "hello")
    },
    test("ctx.squawkKey(_.items, id) tracks one map entry as Squawk[Option[V]]") {
      ZIO.scoped {
        for
          c <- CtxModel.make
          ctx = c.ctx
          s    <- ctx.squawkKey(_.items, "a")
          seed <- s.get
          _    <- ctx(Op.PutItem("a", Item("apple", done = false)))
          _    <- c.run()
          put  <- s.get
          _    <- ctx(Op.RemoveItem("a"))
          _    <- c.run()
          gone <- s.get
        yield assertTrue(
          seed.isEmpty,
          put.contains(Item("apple", done = false)),
          gone.isEmpty,
        )
      }
    },
    test("squawkKey is element-scoped: a different key does not fire this squawk") {
      ZIO.scoped {
        for
          c <- CtxModel.make
          ctx = c.ctx
          s     <- ctx.squawkKey(_.items, "a")
          fires <- Ref.make(0)
          _     <- s.observe(_ => fires.update(_ + 1))
          _     <- ctx(Op.PutItem("b", Item("banana", done = false)))
          _     <- c.run()
          n     <- fires.get
        yield assertTrue(n == 0)
      }
    },
    test("closing the scope unsubscribes squawkKey: no further fan-out after teardown") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        scope      <- Scope.make
        s          <- scope.extend(ctx.squawkKey(_.items, "a"))
        fires      <- Ref.make(0)
        _          <- s.observe(_ => fires.update(_ + 1))
        _          <- ctx(Op.PutItem("a", Item("apple", done = false)))
        _          <- c.run()
        live       <- fires.get
        _          <- scope.close(Exit.unit)
        _          <- ctx(Op.PutItem("a", Item("apricot", done = true)))
        _          <- c.run()
        afterClose <- fires.get
      yield assertTrue(live == 1, afterClose == 1)
    },
    test("ctx.squawkAt(_.queue, i) tracks a list element as Squawk[Option[V]]") {
      ZIO.scoped {
        for
          c <- CtxModel.make
          ctx = c.ctx
          s    <- ctx.squawkAt(_.queue, 0)
          seed <- s.get
          _    <- ctx(Op.PushQueue(7))
          _    <- c.run()
          head <- s.get
        yield assertTrue(seed.isEmpty, head.contains(7))
      }
    },
    test("ctx.squawkAtVector(_.log, i) tracks a vector element as Squawk[Option[V]]") {
      ZIO.scoped {
        for
          c <- CtxModel.make
          ctx = c.ctx
          s    <- ctx.squawkAtVector(_.log, 0)
          seed <- s.get
          _    <- ctx(Op.AppendLog("first"))
          _    <- c.run()
          head <- s.get
        yield assertTrue(seed.isEmpty, head.contains("first"))
      }
    },
    test("ctx.read(_.path) returns the current slice without subscribing") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        v0 <- ctx.read(_.counter)
        _  <- ctx(Op.IncCounter(5))
        _  <- c.run()
        v1 <- ctx.read(_.counter)
      yield assertTrue(v0 == 0, v1 == 5)
    },
    test("ctx.current returns the whole current model") {
      for
        c <- CtxModel.make
        ctx = c.ctx
        m <- ctx.current
      yield assertTrue(m.counter == 0 && m.label == "alpha")
    },
  )
end CtxSpec
