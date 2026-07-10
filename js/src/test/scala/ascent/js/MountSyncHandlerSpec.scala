package ascent.js

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Pins the `runOrFork` dispatch contract: a handler's synchronous prefix runs inline within the browser's
  * event-dispatch turn (so `preventDefault`/`setData` land), forking only at the first real suspension.
  */
object MountSyncHandlerSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  /** Build + dispatch a real cancelable event, returning whether the browser saw it as default-prevented IMMEDIATELY
    * after dispatch returns (i.e. synchronously).
    */
  private def dispatchCancelable(el: js.Dynamic, name: String): Boolean =
    val ev = js.Dynamic.newInstance(js.Dynamic.global.Event)(
      name,
      js.Dynamic.literal(bubbles = true, cancelable = true),
    )
    el.dispatchEvent(ev)
    ev.defaultPrevented.asInstanceOf[Boolean]

  def spec = suite("Mount (synchronous handlers — runOrFork)")(
    test("a synchronous handler's preventDefault takes effect WITHIN the dispatch turn") {
      withParent { parent =>
        for
          handler     = (e: AscentEvent) => ZIO.succeed(e.raw.asInstanceOf[js.Dynamic].preventDefault()).unit
          ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
          _ <- AscentApp.mount(ui, parent)
          btn       = parent.asInstanceOf[js.Dynamic].firstChild
          prevented = dispatchCancelable(btn, "click")
        yield assertTrue(prevented)
      }
    },
    test("a synchronous handler's DOM mutation is visible immediately (no macrotask defer)") {
      withParent { parent =>
        for
          handler = (e: AscentEvent) =>
            ZIO.succeed {
              e.raw
                .asInstanceOf[js.Dynamic]
                .target
                .asInstanceOf[js.Dynamic]
                .setAttribute("data-handled", "yes")
            }.unit
          ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
          _ <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed(btn.click())
          attr = btn.getAttribute("data-handled").asInstanceOf[String]
        yield assertTrue(attr == "yes")
      }
    },
    test("regression: a suspending handler (Queue.offer) still completes via the fork path") {
      withParent { parent =>
        for
          q <- Queue.unbounded[String]
          handler     = (e: AscentEvent) => q.offer(e.targetValue.getOrElse("?")).unit
          ui: UI[Any] = UI.Element("input", Vector(Attr.EventHandler("input", handler)), Vector.empty)
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
          got <- q.take
        yield assertTrue(got == "hello")
      }
    },
    test("regression: a handler that suspends on sleep does not throw and eventually completes") {
      withParent { parent =>
        for
          done <- Promise.make[Nothing, Unit]
          handler     = (_: AscentEvent) => ZIO.sleep(1.millis) *> done.succeed(()).unit
          ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
          _ <- AscentApp.mount(ui, parent)
          btn = parent.asInstanceOf[js.Dynamic].firstChild
          _ <- ZIO.succeed(btn.click())
          _ <- done.await
        yield assertCompletes
      }
    } @@ TestAspect.withLiveClock,
    test("a synchronous handler DEFECT is not swallowed — it escapes the listener to the window error reporter") {
      // A sync defect surfaces inline through runOrFork's getOrThrowFiberFailure and escapes the DOM
      // listener; the browser reports it on window's error event, where we capture it.
      withParent { parent =>
        for caught <- captureWindowError {
            for
              handler     = (_: AscentEvent) => ZIO.succeed(throw new RuntimeException("boom-from-handler"))
              ui: UI[Any] = UI.Element("button", Vector(Attr.EventHandler("click", handler)), Vector.empty)
              _ <- AscentApp.mount(ui, parent)
              btn = parent.asInstanceOf[js.Dynamic].firstChild
              _ <- ZIO.succeed(btn.click())
            yield ()
          }
        yield assertTrue(caught.exists(_.contains("boom-from-handler")))
      }
    },
  )

  /** Run `body`, capturing the first error reported on `window` during it (preventing the default report so an escaping
    * defect doesn't terminate the test run). Returns the message, if any.
    */
  private def captureWindowError(body: UIO[Unit]): UIO[Option[String]] =
    for
      ref <- Ref.make(Option.empty[String])
      win                                  = dom.window.asInstanceOf[js.Dynamic]
      listener: js.Function1[js.Any, Unit] = (e: js.Any) =>
        val ed = e.asInstanceOf[js.Dynamic]
        ed.preventDefault()
        ed.stopImmediatePropagation()
        val msg = Option(ed.message.asInstanceOf[String])
          .orElse(Option(ed.error).map(_.toString))
          .getOrElse(e.toString)
        // Ref.set is a UIO; run it inline on the dispatch stack.
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ref.set(Some(msg))).getOrThrow())
        ()
      _   <- ZIO.succeed(win.addEventListener("error", listener))
      _   <- body
      _   <- ZIO.succeed(win.removeEventListener("error", listener))
      out <- ref.get
    yield out
end MountSyncHandlerSpec
