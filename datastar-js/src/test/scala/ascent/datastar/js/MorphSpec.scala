package ascent.datastar.js

import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** Morph invariants that distinguish a real idiomorph from a wholesale `innerHTML` swap: existing DOM nodes are reused,
  * so focus/caret, node identity across reorders, and untouched subtrees survive an `inner`/`outer` patch.
  */
object MorphSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use)

  private def d(e: Any): js.Dynamic = e.asInstanceOf[js.Dynamic]

  def spec = suite("Morph")(
    test("inner reuses an unchanged child node (identity preserved)") {
      withParent { parent =>
        for
          _ <- ZIO.succeed { d(parent).innerHTML = "<p id=\"a\">hello</p>" }
          before = d(parent).querySelector("#a")
          _ <- ZIO.succeed(Morph.inner(parent, "<p id=\"a\">hello</p>"))
          after = d(parent).querySelector("#a")
        yield assertTrue(before eq after)
      }
    },
    test("a text-only change rewrites .data in place, keeping the element node") {
      withParent { parent =>
        for
          _ <- ZIO.succeed { d(parent).innerHTML = "<p id=\"a\">hello</p>" }
          pBefore = d(parent).querySelector("#a")
          _ <- ZIO.succeed(Morph.inner(parent, "<p id=\"a\">world</p>"))
          pAfter = d(parent).querySelector("#a")
        yield assertTrue(
          pBefore eq pAfter,
          d(pAfter).textContent.asInstanceOf[String] == "world",
        )
      }
    },
    test("an attribute-only change updates the attr without replacing the node") {
      withParent { parent =>
        for
          _ <- ZIO.succeed { d(parent).innerHTML = "<p id=\"a\" class=\"x\">hi</p>" }
          before = d(parent).querySelector("#a")
          _ <- ZIO.succeed(Morph.inner(parent, "<p id=\"a\" class=\"y\">hi</p>"))
          after = d(parent).querySelector("#a")
        yield assertTrue(
          before eq after,
          d(after).getAttribute("class").asInstanceOf[String] == "y",
        )
      }
    },
    test("a focused input survives a re-patch (focus preserved, value not clobbered)") {
      withParent { parent =>
        for
          _ <- ZIO.succeed {
            d(parent).innerHTML = "<input id=\"name\" value=\"\">"
            val input = d(parent).querySelector("#name")
            input.focus()
            input.value = "typing"
          }
          inputBefore = d(parent).querySelector("#name")
          // Server re-renders with an empty value attribute; the user's live-typed value must win.
          _ <- ZIO.succeed(Morph.inner(parent, "<input id=\"name\" value=\"\"><span>label</span>"))
          inputAfter   = d(parent).querySelector("#name")
          stillFocused = dom.document.activeElement.asInstanceOf[js.Any] eq inputAfter.asInstanceOf[js.Any]
        yield assertTrue(
          inputBefore eq inputAfter,
          stillFocused,
          d(inputAfter).value.asInstanceOf[String] == "typing",
        )
      }
    },
    test("keyed children reorder by id, reusing nodes rather than rebuilding") {
      withParent { parent =>
        for
          _ <- ZIO.succeed {
            d(parent).innerHTML = "<li id=\"a\">A</li><li id=\"b\">B</li>"
          }
          aBefore = d(parent).querySelector("#a")
          bBefore = d(parent).querySelector("#b")
          _ <- ZIO.succeed(Morph.inner(parent, "<li id=\"b\">B</li><li id=\"a\">A</li>"))
          firstAfter = d(d(parent).childNodes.item(0))
          aAfter     = d(parent).querySelector("#a")
          bAfter     = d(parent).querySelector("#b")
        yield assertTrue(
          aBefore eq aAfter,
          bBefore eq bAfter,
          firstAfter.getAttribute("id").asInstanceOf[String] == "b",
        )
      }
    },
    test("surplus old children are removed and new ones added") {
      withParent { parent =>
        for
          _ <- ZIO.succeed { d(parent).innerHTML = "<p>one</p><p>two</p><p>three</p>" }
          _ <- ZIO.succeed(Morph.inner(parent, "<p>one</p>"))
        yield assertTrue(d(parent).childNodes.length.asInstanceOf[Int] == 1)
      }
    },
    test("outer morphs the element itself in place when the root tag matches") {
      withParent { parent =>
        for
          _ <- ZIO.succeed { d(parent).innerHTML = "<section id=\"s\">old</section>" }
          before = d(parent).querySelector("#s")
          _ <- ZIO.succeed(
            Morph.outer(before.asInstanceOf[dom.Element], "<section id=\"s\" class=\"on\">new</section>")
          )
          after = d(parent).querySelector("#s")
        yield assertTrue(
          before eq after,
          d(after).getAttribute("class").asInstanceOf[String] == "on",
          d(after).textContent.asInstanceOf[String] == "new",
        )
      }
    },
  ) @@ TestAspect.sequential
end MorphSpec
