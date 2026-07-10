package ascent.squawk

import zio.*
import zio.test.*

/** Squawk is the reactive foundation the binding engine relies on; every guarantee it makes is pinned here. */
object SquawkSpec extends ZIOSpecDefault:

  def spec = suite("Squawk")(
    suite("Source: get/set/update")(
      test("get returns the initial value, set replaces it, update applies a function") {
        for
          s <- sq(0)
          a <- s.get
          _ <- s.set(7)
          b <- s.get
          _ <- s.update(_ + 1)
          c <- s.get
        yield assertTrue(a == 0, b == 7, c == 8)
      },
      test("setting an Eq-equal value does NOT fire observers (dedup)") {
        for
          s     <- sq(0)
          fires <- Ref.make(0)
          _     <- s.observe(_ => fires.update(_ + 1))
          _     <- s.set(0)
          _     <- s.set(0)
          n     <- fires.get
        yield assertTrue(n == 0)
      },
      test("setting a different value fires observers exactly once per distinct change") {
        for
          s     <- sq(0)
          fires <- Ref.make(0)
          _     <- s.observe(_ => fires.update(_ + 1))
          _     <- s.set(1)
          _     <- s.set(2)
          _     <- s.set(2)
          _     <- s.set(3)
          n     <- fires.get
        yield assertTrue(n == 3)
      },
    ),
    suite("Subscription")(
      test("cancel detaches the observer; later sets do not fire it") {
        for
          s     <- sq(0)
          fires <- Ref.make(0)
          sub   <- s.observe(_ => fires.update(_ + 1))
          _     <- s.set(1)
          _     <- sub.cancel
          _     <- s.set(2)
          _     <- s.set(3)
          n     <- fires.get
        yield assertTrue(n == 1)
      },
      test("cancel is idempotent: calling it twice is harmless") {
        for
          s   <- sq(0)
          sub <- s.observe(_ => ZIO.unit)
          _   <- sub.cancel
          _   <- sub.cancel
          _   <- s.set(1)
          v   <- s.get
        yield assertTrue(v == 1)
      },
      test("after cancel, the underlying observer set no longer holds the listener (leak check)") {
        for
          s   <- sq(0)
          sub <- s.observe(_ => ZIO.unit)
          n0  <- s.observerCount
          _   <- sub.cancel
          n1  <- s.observerCount
        yield assertTrue(n0 == 1, n1 == 0)
      },
    ),
    suite("Derived (map): pure construction + propagation")(
      test("Derived's value reflects its source through the map function") {
        for
          s <- sq(3)
          d = s.map(_ * 2)
          a <- d.get
          _ <- s.set(10)
          b <- d.get
        yield assertTrue(a == 6, b == 20)
      },
      test("Observers on a Derived see only changes to the DERIVED value, not the source") {
        for
          s <- sq(1)
          d = s.map(_ % 2 == 0)
          fires <- Ref.make(0)
          _     <- d.observe(_ => fires.update(_ + 1))
          _     <- s.set(3)
          _     <- s.set(5)
          _     <- s.set(7)
          n     <- fires.get
        yield assertTrue(n == 0)
      },
      test("map is a pure constructor: building a Derived runs no effect (no source observer added until observed)") {
        for
          s  <- sq(0)
          n0 <- s.observerCount
          _ = s.map(_ + 1)
          _ = s.map(_.toString)
          n1 <- s.observerCount
        yield assertTrue(n0 == 0, n1 == 0)
      },
    ),
    suite("Observe semantics: edge cases")(
      test("an observer added during notification of another observer fires on the NEXT change, not the current one") {
        for
          s          <- sq(0)
          lateFires  <- Ref.make(0)
          firstFires <- Ref.make(0)
          _          <- s.observe { _ =>
            firstFires.update(_ + 1) *> s.observe(_ => lateFires.update(_ + 1)).unit
          }
          _ <- s.set(1)
          a <- lateFires.get
          _ <- s.set(2)
          b <- lateFires.get
        yield assertTrue(a == 0, b >= 1)
      },
      test("a failing observer does not corrupt the observer set or block other observers") {
        // observe's contract is A => UIO[Any]: no typed error channel, so an observer can only "fail" by dying.
        for
          s        <- sq(0)
          goodHits <- Ref.make(0)
          _        <- s.observe(_ => ZIO.die(new RuntimeException("boom")))
          _        <- s.observe(_ => goodHits.update(_ + 1))
          _        <- s.set(1)
          _        <- s.set(2)
          n        <- goodHits.get
          count    <- s.observerCount
        yield assertTrue(n == 2, count == 2)
      },
    ),
    suite("const")(
      test("holds a fixed value and never observes: observe is a no-op with zero observers") {
        for
          c     <- ZIO.succeed(Squawk.const(42))
          v     <- c.get
          fires <- Ref.make(0)
          sub   <- c.observe(_ => fires.update(_ + 1))
          n0    <- c.observerCount
          _     <- sub.cancel
          n     <- fires.get
        yield assertTrue(v == 42, n0 == 0, n == 0)
      }
    ),
    suite("zipWith")(
      test("reads both sources at observe-time: value tracks each side") {
        for
          a <- sq(1)
          b <- sq(10)
          z = Squawk.zipWith(a, b)(_ + _)
          v0 <- z.get
          _  <- a.set(2)
          v1 <- z.get
          _  <- b.set(20)
          v2 <- z.get
        yield assertTrue(v0 == 11, v1 == 12, v2 == 22)
      },
      test("cancelling the combined subscription detaches BOTH inner observers (leak check)") {
        for
          a <- sq(0)
          b <- sq(0)
          z = Squawk.zipWith(a, b)(_ + _)
          sub <- z.observe(_ => ZIO.unit)
          na0 <- a.observerCount
          nb0 <- b.observerCount
          _   <- sub.cancel
          na1 <- a.observerCount
          nb1 <- b.observerCount
        yield assertTrue(na0 == 1, nb0 == 1, na1 == 0, nb1 == 0)
      },
    ),
    suite("Diamond consistency")(
      test("a Derived built from two Derived of the same Source sees a single consistent value per change") {
        for
          s <- sq(0)
          a = s.map(_ + 1)
          b = s.map(_ * 2)
          d = Squawk.zipWith(a, b)(_ + _)
          seen <- Ref.make[List[Int]](Nil)
          _    <- d.observe(v => seen.update(v :: _))
          _    <- s.set(1)
          _    <- s.set(2)
          xs   <- seen.get
        // 5 and 6 are the mixed-generation partials (a and b built from different s); observing either is a glitch.
        yield assertTrue(
          xs.headOption == Some(7),
          xs.contains(4),
          !xs.contains(5),
          !xs.contains(6),
        )
      }
    ),
    suite("Property: dedup invariant under arbitrary set sequences")(
      test("after any sequence of set(n) calls, get equals the LAST distinct value") {
        check(Gen.listOfBounded(0, 30)(Gen.int(-3, 3))) { values =>
          for
            s <- sq(Int.MinValue)
            _ <- ZIO.foreachDiscard(values)(s.set)
            v <- s.get
          yield
            val expected = values.lastOption.getOrElse(Int.MinValue)
            assertTrue(v == expected)
        }
      },
      test("observer fire count never exceeds the number of distinct consecutive values in the set sequence") {
        check(Gen.listOfBounded(0, 30)(Gen.int(-3, 3))) { values =>
          for
            s     <- sq(Int.MinValue)
            fires <- Ref.make(0)
            _     <- s.observe(_ => fires.update(_ + 1))
            _     <- ZIO.foreachDiscard(values)(s.set)
            n     <- fires.get
          yield assertTrue(n <= values.size)
        }
      },
    ),
  )
end SquawkSpec
