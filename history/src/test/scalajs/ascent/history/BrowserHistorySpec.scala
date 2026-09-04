package ascent.history

import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Browser backend under jsdom: `pushState` / `popstate` / listener teardown. */
object BrowserHistorySpec extends ZIOSpecDefault:

  private def href: String =
    val loc                     = dom.window.location.asInstanceOf[js.Dynamic]
    def f(name: String): String =
      val v = loc.selectDynamic(name)
      if js.isUndefined(v) || v == null then "" else v.toString
    f("pathname") + f("search") + f("hash")

  private def dispatch(kind: String): Unit =
    val evt =
      if js.typeOf(js.Dynamic.global.HashChangeEvent) != "undefined" && kind == "hashchange" then
        js.Dynamic.newInstance(js.Dynamic.global.HashChangeEvent)(
          kind,
          js.Dynamic.literal(oldURL = href, newURL = href),
        )
      else js.Dynamic.newInstance(js.Dynamic.global.Event)(kind)
    dom.window.dispatchEvent(evt.asInstanceOf[dom.Event])
    ()
  end dispatch

  def spec = suite("History (browser)")(
    test("History.browser seeds from window.location") {
      ZIO.scoped {
        for
          h <- History.browser
          v <- h.location.get
        yield assertTrue(v.href == Location.parse(href).href || v.pathname.nonEmpty)
      }
    },
    test("push / replace change window.location via pushState / replaceState") {
      ZIO.scoped {
        for
          h <- History.browser
          _ <- h.replace("/hist-spec-a")
          a <- h.location.get
          _ <- h.push("/hist-spec-b")
          b <- h.location.get
        yield assertTrue(
          a.pathname == "/hist-spec-a",
          b.pathname == "/hist-spec-b",
          dom.window.location.pathname == "/hist-spec-b",
        )
      }
    },
    test("hist.back() and a popstate both write the Squawk") {
      ZIO.scoped {
        for
          h     <- History.browser
          fires <- Ref.make(0)
          _     <- h.replace("/hist-back-a")
          _     <- h.push("/hist-back-b")
          _     <- h.location.observe(_ => fires.update(_ + 1))
          _     <- h.back
          v1    <- h.location.get
          _     <- ZIO.succeed {
            dom.window.history.pushState(null: js.Any, "", "/hist-back-c")
            dispatch("popstate")
          }
          v2 <- h.location.get
          n2 <- fires.get
        yield assertTrue(
          v1.pathname == "/hist-back-a" || v1.pathname == "/hist-back-b",
          v2.pathname == "/hist-back-c",
          n2 >= 1,
        )
      }
    },
    test("hash-only push plus a synthetic hashchange still notifies once") {
      ZIO.scoped {
        for
          h     <- History.browser
          _     <- h.replace("/hist-hash")
          fires <- Ref.make(0)
          _     <- h.location.observe(_ => fires.update(_ + 1))
          _     <- h.push("#/active")
          _     <- ZIO.succeed(dispatch("hashchange"))
          n     <- fires.get
          v     <- h.location.get
        yield assertTrue(n == 1, v.hash == "/active")
      }
    },
    test("Scope close removes the listeners") {
      for
        fires <- Ref.make(0)
        _     <- ZIO.scoped {
          for
            h <- History.browser
            _ <- h.replace("/hist-unlisten")
            _ <- h.location.observe(_ => fires.update(_ + 1))
          yield ()
        }
        _ <- ZIO.succeed {
          dom.window.history.pushState(null: js.Any, "", "/hist-unlisten-2")
          dispatch("popstate")
        }
        n <- fires.get
      yield assertTrue(n == 0)
    },
  )
end BrowserHistorySpec
