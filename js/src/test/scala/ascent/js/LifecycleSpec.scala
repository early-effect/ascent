package ascent.js

import ascent.ast.UI
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** [[Lifecycle]]'s hooks pin the element type and check the live node against it: a hook runs on an element of the type
  * it takes, and on any other it does not run and logs why.
  */
object LifecycleSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement(dom.HtmlTag.div)
        dom.document.body.foreach(_.appendChild(p))
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.foreach(_.removeChild(p))))(use = use)

  /** Every tag once: `checkAll` over a finite set is exhaustive, where sampling would repeat and miss. */
  private val tags = Gen.fromIterable(List("input", "div", "canvas", "p", "button", "textarea"))

  def spec = suite("Lifecycle")(
    test("an onMount pinned to an interface runs exactly on the elements of that interface") {
      checkAll(tags) { tag =>
        withParent { parent =>
          for
            ran <- Ref.make(false)
            ui: UI[Any] = UI
              .Element(tag, Vector(Lifecycle.onMount[dom.HTMLInputElement](_ => ran.set(true))), Vector.empty)
            _   <- AscentApp.mount(ui, parent)
            got <- ran.get
          yield assertTrue(got == (tag == "input"))
        }
      }
    },
    test("the handler gets the live element, typed as the interface it names") {
      withParent { parent =>
        for
          seen <- Ref.make(Option.empty[dom.HTMLCanvasElement])
          ui: UI[Any] = UI.Element(
            "canvas",
            Vector(Lifecycle.onMount[dom.HTMLCanvasElement](c => seen.set(Some(c)))),
            Vector.empty,
          )
          _   <- AscentApp.mount(ui, parent)
          got <- seen.get
        yield assertTrue(
          got.exists(c => parent.firstChild.exists(js.special.strictEquals(_, c))),
          got.exists(_.width > 0),
        )
      }
    },
    test("left unpinned, a hook takes any element") {
      checkAll(tags) { tag =>
        withParent { parent =>
          for
            seen <- Ref.make(Option.empty[String])
            ui: UI[Any] = UI.Element(tag, Vector(Lifecycle.onMount(el => seen.set(Some(el.localName)))), Vector.empty)
            _   <- AscentApp.mount(ui, parent)
            got <- seen.get
          yield assertTrue(got.contains(tag))
        }
      }
    },
    test("a hook on an element of another type logs that it did not run, naming the element") {
      withParent { parent =>
        for
          _ <- AscentApp.mount(
            UI.Element("div", Vector(Lifecycle.onMount[dom.HTMLCanvasElement](_ => ZIO.unit)), Vector.empty),
            parent,
          )
          logs <- ZTestLogger.logOutput
          said = logs.filter(_.logLevel == LogLevel.Warning).map(_.message())
        yield assertTrue(said.exists(m => m.contains("Lifecycle.onMount did not run") && m.contains("<div>")))
      }
    },
    test("onUnmount runs on cleanup only for its own interface") {
      withParent { parent =>
        for
          ran <- Ref.make(Vector.empty[String])
          ui: UI[Any] = UI.Element(
            "div",
            Vector(
              Lifecycle.onUnmount[dom.HTMLDivElement](_ => ran.update(_ :+ "div")),
              Lifecycle.onUnmount[dom.HTMLInputElement](_ => ran.update(_ :+ "input")),
            ),
            Vector.empty,
          )
          cleanup <- AscentApp.mount(ui, parent)
          before  <- ran.get
          _       <- cleanup.cancelAll
          after   <- ran.get
        yield assertTrue(before.isEmpty, after == Vector("div"))
      }
    },
    test("onMountScoped acquires only on its own interface, and releases on cleanup") {
      withParent { parent =>
        for
          events <- Ref.make(Vector.empty[String])
          scoped = (name: String) =>
            ZIO.acquireRelease(events.update(_ :+ s"$name up"))(_ => events.update(_ :+ s"$name down")).unit
          ui: UI[Any] = UI.Element(
            "p",
            Vector(
              Lifecycle.onMountScoped[dom.HTMLParagraphElement](_ => scoped("p")),
              Lifecycle.onMountScoped[dom.HTMLCanvasElement](_ => scoped("canvas")),
            ),
            Vector.empty,
          )
          cleanup <- AscentApp.mount(ui, parent)
          mounted <- events.get
          _       <- cleanup.cancelAll
          after   <- events.get
        yield assertTrue(mounted == Vector("p up"), after == Vector("p up", "p down"))
      }
    },
  ) @@ TestAspect.timeout(60.seconds)
end LifecycleSpec
