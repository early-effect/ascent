package ascent.js

import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** [[DomStyleSink]] writes keyed CSS blocks into `<style>` elements under `document.head`; re-appending a key replaces
  * the existing block in place (same `<style>` node identity) rather than duplicating.
  */
object DomStyleSinkSpec extends ZIOSpecDefault:

  /** Remove every `<style data-ascent-class>` left over from a prior test. */
  private val cleanHead: UIO[Unit] = ZIO.succeed {
    val head  = dom.document.asInstanceOf[js.Dynamic].head
    val nodes = head.querySelectorAll("style[data-ascent-class]")
    val n     = nodes.length.asInstanceOf[Int]
    var i     = 0
    while i < n do
      val node = nodes.item(i).asInstanceOf[js.Dynamic]
      head.removeChild(node)
      i += 1
  }

  private def styleForKey(key: String): js.Dynamic =
    dom.document
      .asInstanceOf[js.Dynamic]
      .head
      .querySelector(s"""style[data-ascent-class="$key"]""")
      .asInstanceOf[js.Dynamic]

  def spec = suite("DomStyleSink")(
    test("appending a key creates a <style data-ascent-class=key> in <head> with the rule body") {
      for
        _ <- cleanHead
        sink = DomStyleSink
        _ <- sink.append("foo", ".foo { color: red; }")
      yield
        val style = styleForKey("foo")
        assertTrue(
          style != null,
          style.textContent.asInstanceOf[String].contains("color: red;"),
          style.tagName.asInstanceOf[String].toLowerCase == "style",
        )
    },
    test("appending the same key twice replaces the rule body but keeps the same <style> element") {
      for
        _ <- cleanHead
        sink = DomStyleSink
        _ <- sink.append("bar", ".bar { color: red; }")
        first = styleForKey("bar").asInstanceOf[js.Any]
        _ <- sink.append("bar", ".bar { color: blue; }")
        second = styleForKey("bar").asInstanceOf[js.Any]
      yield assertTrue(
        first eq second,
        styleForKey("bar").textContent.asInstanceOf[String].contains("color: blue;"),
        !styleForKey("bar").textContent.asInstanceOf[String].contains("color: red;"),
      )
    },
    test("two distinct keys produce two distinct <style> elements") {
      for
        _ <- cleanHead
        sink = DomStyleSink
        _ <- sink.append("a", ".a { color: red; }")
        _ <- sink.append("b", ".b { color: blue; }")
      yield
        val a = styleForKey("a")
        val b = styleForKey("b")
        assertTrue(
          a != null,
          b != null,
          (a.asInstanceOf[js.Any] ne b.asInstanceOf[js.Any]),
          a.textContent.asInstanceOf[String].contains(".a"),
          b.textContent.asInstanceOf[String].contains(".b"),
        )
    },
    test("integrates with CssClass.installInto so authoring code stays platform-neutral") {
      import ascent.css.{CssClass, Declaration}
      object Banner extends CssClass(Declaration("background", "yellow"))
      for
        _ <- cleanHead
        _ <- Banner.installInto(DomStyleSink)
      yield
        val style = styleForKey(Banner.className)
        assertTrue(
          style != null,
          style.textContent.asInstanceOf[String].contains("background: yellow;"),
          style.textContent.asInstanceOf[String].contains(s".${Banner.className}"),
        )
      end for
    },
    test("EventTarget.addCssClass injects the class's CSS into <head> AND adds the token; removeCssClass drops it") {
      import ascent.css.{CssClass, Declaration}
      object Highlight extends CssClass(Declaration("outline", "2px solid red"))
      for
        _ <- cleanHead
        el = dom.document.createElement("div")
        _ <- ZIO.succeed(el.addCssClass(Highlight))
        afterAdd      = el.getAttribute("class")
        styleAfterAdd = styleForKey(Highlight.className)
        _ <- ZIO.succeed(el.removeCssClass(Highlight))
        afterRemove = el.getAttribute("class")
      yield assertTrue(
        // token added, and the CSS is in <head> even though no element with this class ever mounted
        afterAdd.contains(Highlight.className),
        styleAfterAdd != null,
        styleAfterAdd.textContent.asInstanceOf[String].contains("outline: 2px solid red;"),
        // token removed; the <style> stays (harmless — likely re-added next toggle)
        !afterRemove.split(" ").contains(Highlight.className),
      )
      end for
    },
    test("addCssClass twice injects exactly one <style> (idempotent by key)") {
      import ascent.css.{CssClass, Declaration}
      object Dup extends CssClass(Declaration("color", "green"))
      for
        _ <- cleanHead
        el1 = dom.document.createElement("div")
        el2 = dom.document.createElement("div")
        _ <- ZIO.succeed(el1.addCssClass(Dup))
        _ <- ZIO.succeed(el2.addCssClass(Dup))
        count = dom.document
          .asInstanceOf[js.Dynamic]
          .head
          .querySelectorAll(s"""style[data-ascent-class="${Dup.className}"]""")
          .length
          .asInstanceOf[Int]
      yield assertTrue(count == 1)
      end for
    },
  )
end DomStyleSinkSpec
