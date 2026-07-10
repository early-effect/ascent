package ascent.datastar.js

import ascent.datastar.SignalStore
import zio.*
import zio.test.*

import scala.scalajs.js

/** Outgoing action dispatch, with a stubbed global `fetch` capturing the request the client sends: the
  * `Datastar-Request` header and a body that is the store's snapshot JSON (the exact shape the SDK's `readSignals`
  * decodes).
  */
object ActionSpec extends ZIOSpecDefault:

  /** Install a fake `window.fetch` that records its (url, init) and resolves; restore afterwards. */
  private def withStubbedFetch[A](capture: Ref[Option[(String, js.Dynamic)]])(use: UIO[A]): UIO[A] =
    val g = js.Dynamic.global.window
    ZIO.acquireReleaseWith(
      ZIO.succeed {
        val original = g.fetch
        g.fetch = (url: js.Any, init: js.Any) =>
          Unsafe.unsafe { implicit u =>
            Runtime.default.unsafe
              .run(capture.set(Some((url.asInstanceOf[String], init.asInstanceOf[js.Dynamic]))))
              .getOrThrow()
          }
          js.Dynamic.global.Promise.resolve(js.Dynamic.literal())
        original
      }
    )(original => ZIO.succeed { g.fetch = original })(_ => use)
  end withStubbedFetch

  def spec = suite("Action")(
    test("post sends the Datastar-Request header and the snapshot JSON body") {
      for
        captured <- Ref.make(Option.empty[(String, js.Dynamic)])
        store    <- SignalStore.make()
        _        <- store.squawk("count", 0)
        _        <- store.squawk("name", "x")
        _        <- store.route(ascent.datastar.SignalPatch.Put("count", zio.json.ast.Json.Num(5), false))
        _        <- withStubbedFetch(captured) {
          Action.post(store, "/inc")
        }
        rec <- captured.get
      yield
        val (url, init) = rec.get
        val method      = init.method.asInstanceOf[String]
        val header      = init.headers.selectDynamic("Datastar-Request").asInstanceOf[String]
        val body        = init.body.asInstanceOf[String]
        assertTrue(
          url == "/inc",
          method == "POST",
          header == "true",
          body.contains("\"count\":5"),
          body.contains("\"name\":\"x\""),
        )
    },
    test("get encodes signals into the datastar query parameter") {
      for
        captured <- Ref.make(Option.empty[(String, js.Dynamic)])
        store    <- SignalStore.make()
        _        <- store.squawk("count", 7)
        _        <- withStubbedFetch(captured)(Action.get(store, "/load"))
        rec      <- captured.get
      yield
        val (url, init) = rec.get
        assertTrue(
          url.startsWith("/load?datastar="),
          url.contains("count"),
          init.method.asInstanceOf[String] == "GET",
        )
    },
  ) @@ TestAspect.sequential
end ActionSpec
