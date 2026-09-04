package ascent.history

import zio.*
import zio.test.*

/** Memory backend: stack vs Squawk equality, resolve, query, and observer contracts. */
object HistorySpec extends ZIOSpecDefault:

  private def loc(path: String, search: String = "", hash: String = ""): Location =
    Location(path, search, hash)

  def spec = suite("History (memory)")(
    suite("Location")(
      test("empty pathname becomes /, punctuation is stripped") {
        val l = Location("", "?a=1", "#/x")
        assertTrue(l.pathname == "/", l.search == "a=1", l.hash == "/x", l.href == "/?a=1#/x")
      },
      test("parse covers path, query, hash, and combinations") {
        assertTrue(
          Location.parse("").href == "/",
          Location.parse("/s").pathname == "/s",
          Location.parse("?scene=empty").search == "scene=empty",
          Location.parse("?scene=empty").pathname == "/",
          Location.parse("#/active").hash == "/active",
          Location.parse("#/active").pathname == "/",
          Location.parse("/s?x=1#h").href == "/s?x=1#h",
        )
      },
      test("resolve: hash keeps pathname and search; query keeps pathname") {
        val cur = loc("/app", "keep=1", "old")
        assertTrue(
          cur.resolve("#/active") == loc("/app", "keep=1", "/active"),
          cur.resolve("?scene=empty") == loc("/app", "scene=empty", ""),
          cur.resolve("?scene=empty#x") == loc("/app", "scene=empty", "x"),
          cur.resolve("/other?a=1#h") == loc("/other", "a=1", "h"),
        )
      },
      test("param is first-wins; missing key is None") {
        val l = loc("/", "scene=empty&scene=other&bare", "")
        assertTrue(l.param("scene").contains("empty"), l.param("bare").contains(""), l.param("no").isEmpty)
      },
    ),
    suite("stack vs Squawk")(
      test("memory() length is 1; each push increments; replace does not") {
        for
          h  <- History.memory()
          n0 <- h.length
          _  <- h.push("/a")
          n1 <- h.length
          _  <- h.replace("/b")
          n2 <- h.length
          v  <- h.location.get
        yield assertTrue(n0 == 1, n1 == 2, n2 == 2, v == loc("/b"))
      },
      test("Eq-equal push still grows length but observers do not fire") {
        for
          h     <- History.memory()
          _     <- h.push("/a")
          n0    <- h.length
          fires <- Ref.make(0)
          _     <- h.location.observe(_ => fires.update(_ + 1))
          _     <- h.push("/a")
          n1    <- h.length
          n     <- fires.get
        yield assertTrue(n1 == n0 + 1, n == 0)
      },
      test("replace of a new URL overwrites and notifies; replace of the current URL does neither extra") {
        for
          h     <- History.memory()
          _     <- h.push("/a")
          fires <- Ref.make(0)
          _     <- h.location.observe(_ => fires.update(_ + 1))
          _     <- h.replace("/b")
          n0    <- fires.get
          len0  <- h.length
          _     <- h.replace("/b")
          n1    <- fires.get
          len1  <- h.length
          v     <- h.location.get
        yield assertTrue(n0 == 1, n1 == 1, len0 == len1, v == loc("/b"))
      },
      test("push then push then back restores the previous Location; a later push drops the forward entry") {
        for
          h <- History.memory()
          _ <- h.push("/a")
          _ <- h.push("/b")
          _ <- h.back
          a <- h.location.get
          _ <- h.push("/c")
          _ <- h.forward
          c <- h.location.get
        yield assertTrue(a == loc("/a"), c == loc("/c"))
      },
      test("back / forward / go at the ends of the stack are no-ops") {
        for
          h <- History.memory()
          _ <- h.back
          _ <- h.go(-1)
          r <- h.location.get
          _ <- h.push("/a")
          _ <- h.forward
          _ <- h.go(2)
          a <- h.location.get
        yield assertTrue(r == Location.root, a == loc("/a"))
      },
      test("string push resolves against current") {
        for
          h <- History.memory(loc("/app", "keep=1"))
          _ <- h.push("#/active")
          v <- h.location.get
        yield assertTrue(v == loc("/app", "keep=1", "/active"))
      },
    ),
    suite("observers")(
      test("Scope close of a scoped observe unsubscribes (no fire after close)") {
        for
          h     <- History.memory()
          fires <- Ref.make(0)
          _     <- ZIO.scoped(h.location.observe(_ => fires.update(_ + 1)).flatMap(sub => ZIO.addFinalizer(sub.cancel)))
          n0    <- h.location.observerCount
          _     <- h.push("/a")
          n     <- fires.get
        yield assertTrue(n0 == 0, n == 0)
      },
      test("failing observer does not break a later push") {
        for
          h <- History.memory()
          _ <- h.location.observe(_ => ZIO.die(new RuntimeException("boom")))
          _ <- h.push("/a")
          v <- h.location.get
        yield assertTrue(v == loc("/a"))
      },
    ),
  )
end HistorySpec
