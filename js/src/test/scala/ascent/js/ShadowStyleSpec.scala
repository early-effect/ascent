package ascent.js

import ascent.*
import ascent.css.{CssClass, Declaration}
import ascent.dsl.*
import zio.*
import zio.test.*

/** A shadow root sees none of the document's `<head>`, so what renders inside one is styled inside it. */
object ShadowStyleSpec extends ZIOSpecDefault:
  object Inside  extends CssClass(Declaration("color", "rebeccapurple"))
  object Toggled extends CssClass(Declaration("outline", "1px dotted teal"))

  private def selector(cls: CssClass) = s"""style[data-ascent-class="${cls.className}"]"""

  /** A fresh open shadow root on a detached host. */
  private val shadow: UIO[dom.ShadowRoot] =
    ZIO.succeed {
      val host = dom.document.createElement("div")
      dom.document.body.foreach(_.appendChild(host))
      host.attachShadow(new dom.ShadowRootInit:
        mode = "open")
    }

  def spec = suite("styles inside a shadow root")(
    test("a render mounted in a shadow root puts its styles in that root, and none in <head>"):
      for
        root <- shadow
        into <- ZIO.succeed {
          val div = dom.document.createElement("div")
          val _   = root.appendChild(div)
          div
        }
        _ <- AscentApp.mount(E.p(Inside, "hi"), into)
      yield assertTrue(
        root.querySelector(selector(Inside)).flatMap(_.textContent).exists(_.contains("rebeccapurple")),
        dom.document.head.flatMap(_.querySelector(selector(Inside))).isEmpty,
      )
    ,
    test("addCssClass on an element inside a shadow root styles that root"):
      for
        root <- shadow
        el   <- ZIO.succeed {
          val div = dom.document.createElement("div")
          val _   = root.appendChild(div)
          div.addCssClass(Toggled)
          div
        }
      yield assertTrue(
        el.getAttribute("class").exists(_.split(" ").contains(Toggled.className)),
        root.querySelector(selector(Toggled)).isDefined,
        dom.document.head.flatMap(_.querySelector(selector(Toggled))).isEmpty,
      )
    ,
    test("a node outside any shadow root is styled in <head>"):
      ZIO.succeed(assertTrue(StyleTarget.of(dom.document.createElement("div")) == StyleTarget.Head)),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
end ShadowStyleSpec
