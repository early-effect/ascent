package ascent.js

import ascent.ast.{Attr, AstId, IdMode, UI}
import ascent.dom
import ascent.domtypes.AttrValue
import ascent.squawk.{Eq, sq}
import zio.*
import zio.test.*

import scala.scalajs.js

/** The Mount contract: every element is stamped with a structural id (`data-ascent`), [[UI.Empty]] emits no DOM at all,
  * and dynamic boundaries own their DOM via in-memory state — no anchor comments anywhere in the document.
  */
object MountIdSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  /** Recursively count comment nodes anywhere in the tree rooted at `node`. */
  private def commentDescendants(node: dom.Node): Int =
    val dyn   = node.asInstanceOf[js.Dynamic]
    var count = 0
    val cs    = dyn.childNodes
    val n     = cs.length.asInstanceOf[Int]
    var i     = 0
    while i < n do
      val child = cs.item(i)
      if child.nodeType.asInstanceOf[Int] == 8 then count += 1
      else count += commentDescendants(child.asInstanceOf[dom.Node])
      i += 1
    count
  end commentDescendants

  def spec = suite("Mount (post-refactor: structural ids, no comment anchors)")(
    suite("data-ascent on elements")(
      test("every emitted Element carries a data-ascent attribute") {
        withParent { parent =>
          val ui: UI[Any] = UI.Element(
            "div",
            Vector.empty,
            Vector(
              UI.Element("span", Vector.empty, Vector(UI.Text("a"))),
              UI.Element("span", Vector.empty, Vector(UI.Text("b"))),
            ),
          )
          for _ <- AscentApp.mount(ui, parent)
          yield
            val divEl = parent.asInstanceOf[js.Dynamic].firstChild
            val spans = divEl.children
            assertTrue(
              divEl.getAttribute("data-ascent").asInstanceOf[String] != null,
              spans.item(0).getAttribute("data-ascent").asInstanceOf[String] != null,
              spans.item(1).getAttribute("data-ascent").asInstanceOf[String] != null,
            )
          end for
        }
      },
      test("the data-ascent value matches AstId.renderAttr(AstId.compute(astNode))") {
        withParent { parent =>
          val span: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.Text("hi")))
          for _ <- AscentApp.mount(span, parent)
          yield
            val el       = parent.asInstanceOf[js.Dynamic].firstChild
            val expected = AstId.renderAttr(AstId.compute(span))
            assertTrue(el.getAttribute("data-ascent").asInstanceOf[String] == expected)
        }
      },
      test("two structurally-identical sub-trees have the same data-ascent (registry-free hash mode)") {
        withParent { parent =>
          val sibling: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.Text("hi")))
          val ui: UI[Any]      = UI.Element("div", Vector.empty, Vector(sibling, sibling))
          for _ <- AscentApp.mount(ui, parent, IdMode.Hash)
          yield
            val spans = parent.asInstanceOf[js.Dynamic].firstChild.children
            assertTrue(
              spans.item(0).getAttribute("data-ascent").asInstanceOf[String] ==
                spans.item(1).getAttribute("data-ascent").asInstanceOf[String]
            )
        }
      },
      test("two structurally-identical sub-trees share an id by case-class equality (registry idempotence)") {
        // The registry only fans out into tiebreakers for structurally DISTINCT hash collisions
        // (covered in ascent.ast.AstIdSpec); here the Mount contract is just "structurally equal -> same id".
        withParent { parent =>
          val sibling: UI[Any] = UI.Element("span", Vector.empty, Vector(UI.Text("hi")))
          val ui: UI[Any]      = UI.Element("div", Vector.empty, Vector(sibling, sibling))
          for _ <- AscentApp.mount(ui, parent /*, default IdMode.HashWithRegistry */ )
          yield
            val spans = parent.asInstanceOf[js.Dynamic].firstChild.children
            assertTrue(
              spans.item(0).getAttribute("data-ascent").asInstanceOf[String] ==
                spans.item(1).getAttribute("data-ascent").asInstanceOf[String]
            )
        }
      },
    ),
    suite("UI.Empty produces no DOM")(
      test("a top-level UI.Empty mount leaves the parent with zero child nodes") {
        withParent { parent =>
          for _ <- AscentApp.mount(UI.Empty, parent)
          yield
            val cs = parent.asInstanceOf[js.Dynamic].childNodes
            assertTrue(cs.length.asInstanceOf[Int] == 0)
        }
      },
      test("a Fragment containing Empty entries renders only the non-empty ones") {
        withParent { parent =>
          val ui: UI[Any] = UI.Fragment(
            Vector(
              UI.Empty,
              UI.Element("span", Vector.empty, Vector(UI.Text("a"))),
              UI.Empty,
              UI.Element("span", Vector.empty, Vector(UI.Text("b"))),
              UI.Empty,
            )
          )
          for _ <- AscentApp.mount(ui, parent)
          yield
            val cs = parent.asInstanceOf[js.Dynamic].childNodes
            assertTrue(
              cs.length.asInstanceOf[Int] == 2,
              commentDescendants(parent) == 0,
            )
        }
      },
    ),
    suite("Dynamic boundaries leave NO comment markers in the DOM")(
      test("a When that's initially false leaves zero comment nodes anywhere") {
        withParent { parent =>
          for
            cond <- sq(false)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(UI.When(cond, () => UI.Element("p", Vector.empty, Vector(UI.Text("body"))))),
            )
            _ <- AscentApp.mount(ui, parent)
          yield assertTrue(commentDescendants(parent) == 0)
        }
      },
      test("a When that's initially true also leaves zero comment nodes") {
        withParent { parent =>
          for
            cond <- sq(true)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(UI.When(cond, () => UI.Element("p", Vector.empty, Vector(UI.Text("body"))))),
            )
            _ <- AscentApp.mount(ui, parent)
          yield assertTrue(commentDescendants(parent) == 0)
        }
      },
      test("a When that toggles false→true→false→true leaves the DOM clean each time") {
        withParent { parent =>
          for
            cond <- sq(true)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(UI.When(cond, () => UI.Element("p", Vector.empty, Vector(UI.Text("body"))))),
            )
            _ <- AscentApp.mount(ui, parent)
            _ <- cond.set(false)
            _ <- cond.set(true)
            _ <- cond.set(false)
            _ <- cond.set(true)
          yield
            val divEl    = parent.asInstanceOf[js.Dynamic].firstChild
            val children = divEl.childNodes
            assertTrue(
              commentDescendants(parent) == 0,
              children.length.asInstanceOf[Int] == 1,
              children.item(0).tagName.asInstanceOf[String].toLowerCase == "p",
            )
        }
      },
      test("a ForEach with three items leaves zero comment nodes in the DOM") {
        withParent { parent =>
          for
            items <- sq(Seq("a", "b", "c"))
            ui: UI[Any] = UI.Element(
              "ul",
              Vector.empty,
              Vector(UI.ForEach(items, identity, s => UI.Element("li", Vector.empty, Vector(UI.Text(s))))),
            )
            _ <- AscentApp.mount(ui, parent)
          yield assertTrue(commentDescendants(parent) == 0)
        }
      },
    ),
    suite("Sibling-walk insertion: empty boundaries don't break placement")(
      test("when(false), then static element: when→true inserts the body BEFORE the static element") {
        // With no anchors, the runtime finds the insertion point by walking later siblings to `marker`
        // and doing insertBefore(body, marker).
        withParent { parent =>
          for
            cond <- sq(false)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(
                UI.When(cond, () => UI.Element("p", Vector.empty, Vector(UI.Text("body")))),
                UI.Element("span", Vector(Attr.StaticAttr("class", AttrValue.Str("marker"))), Vector(UI.Text("marker"))),
              ),
            )
            _ <- AscentApp.mount(ui, parent)
            _ <- cond.set(true)
          yield
            val divEl    = parent.asInstanceOf[js.Dynamic].firstChild
            val children = divEl.children
            assertTrue(
              children.length.asInstanceOf[Int] == 2,
              children.item(0).tagName.asInstanceOf[String].toLowerCase == "p",
              children.item(1).tagName.asInstanceOf[String].toLowerCase == "span",
            )
        }
      },
      test(
        "when(false), when(true), static: outer when transitioning to true puts body BEFORE the inner when's content"
      ) {
        // Proves the sibling-walk skips empty boundaries and settles on the first non-empty later sibling.
        withParent { parent =>
          for
            condA <- sq(false)
            condB <- sq(true)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(
                UI.When(condA, () => UI.Element("p", Vector.empty, Vector(UI.Text("A")))),
                UI.When(condB, () => UI.Element("p", Vector.empty, Vector(UI.Text("B")))),
                UI.Element("span", Vector.empty, Vector(UI.Text("static"))),
              ),
            )
            _ <- AscentApp.mount(ui, parent)
            _ <- condA.set(true)
          yield
            val children = parent.asInstanceOf[js.Dynamic].firstChild.children
            val texts    = (0 until children.length
              .asInstanceOf[Int]).map(i => children.item(i).textContent.asInstanceOf[String]).toList
            assertTrue(texts == List("A", "B", "static"))
        }
      },
      test("all later siblings empty: when→true content appends at the end") {
        // When no later sibling has content, the sibling-walk falls through to appending at the parent's end.
        withParent { parent =>
          for
            condA <- sq(true)
            condB <- sq(false)
            condC <- sq(false)
            ui: UI[Any] = UI.Element(
              "div",
              Vector.empty,
              Vector(
                UI.When(condA, () => UI.Element("p", Vector.empty, Vector(UI.Text("A")))),
                UI.When(condB, () => UI.Element("p", Vector.empty, Vector(UI.Text("B")))),
                UI.When(condC, () => UI.Element("p", Vector.empty, Vector(UI.Text("C")))),
              ),
            )
            _ <- AscentApp.mount(ui, parent)
            _ <- condA.set(false)
            _ <- condA.set(true)
          yield
            val divEl    = parent.asInstanceOf[js.Dynamic].firstChild
            val children = divEl.children
            assertTrue(
              children.length.asInstanceOf[Int] == 1,
              children.item(0).textContent.asInstanceOf[String] == "A",
              commentDescendants(parent) == 0,
            )
        }
      },
    ),
  )
end MountIdSpec
