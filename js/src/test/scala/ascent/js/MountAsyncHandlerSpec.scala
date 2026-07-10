package ascent.js

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Regression: an event handler whose effect suspends through the ZIO FiberRuntime (Queue.offer, Promise.await, …) must
  * complete rather than throw `Cannot block for result to be set in JavaScript` — the failure mode when a suspending
  * effect is run-blocked on Scala.js instead of forked onto the macrotask scheduler.
  */
object MountAsyncHandlerSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (async handlers)")(
    test("a handler that offers to a Queue does not throw 'Cannot block for result' on JS") {
      withParent { parent =>
        for
          q <- Queue.unbounded[String]
          handler     = (e: AscentEvent) => q.offer(e.targetValue.getOrElse("?")).unit
          ui: UI[Any] = UI.Element(
            "input",
            Vector(Attr.EventHandler("input", handler)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          input = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed {
            input.value = "hello"
            val ev = js.Dynamic.newInstance(js.Dynamic.global.Event)(
              "input",
              js.Dynamic.literal(bubbles = true),
            )
            input.dispatchEvent(ev)
          }
          received <- q.take
        yield assertTrue(received == "hello")
      }
    },
    test("multiple async-handler events queue and process in order") {
      withParent { parent =>
        for
          q       <- Queue.unbounded[Int]
          counter <- Ref.make(0)
          handler     = (_: AscentEvent) => counter.updateAndGet(_ + 1).flatMap(q.offer).unit
          ui: UI[Any] = UI.Element(
            "button",
            Vector(Attr.EventHandler("click", handler)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _  <- ZIO.succeed { btn.click(); btn.click(); btn.click() }
          xs <- ZIO.foreach(1 to 3)(_ => q.take)
        yield assertTrue(xs == Chunk(1, 2, 3))
      }
    },
    test("a handler whose effect suspends asynchronously (sleep) does not crash the listener") {
      // withLiveClock is required: under TestClock the sleep never elapses on its own, so `done.await` would hang.
      withParent { parent =>
        for
          done <- Promise.make[Nothing, Unit]
          handler     = (_: AscentEvent) => ZIO.sleep(1.millis) *> done.succeed(()).unit
          ui: UI[Any] = UI.Element(
            "button",
            Vector(Attr.EventHandler("click", handler)),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed(btn.click())
          _ <- done.await
        yield assertCompletes
      }
    } @@ TestAspect.withLiveClock,
  )
end MountAsyncHandlerSpec
