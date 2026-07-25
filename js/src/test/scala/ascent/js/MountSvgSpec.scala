package ascent.js

import ascent.ast.UI
import ascent.dom
import zio.*
import zio.test.*

import scala.scalajs.js

/** SVG (and MathML) elements must be created with `document.createElementNS`. HTML-namespace `<svg>`/`<g>`/`<text>`
  * nodes do not paint: the browser treats them as unknown HTML and flattens descendant text. Specular remounts diagrams
  * client-side, so this is the contract that keeps live examples from collapsing after hydration.
  */
object MountSvgSpec extends ZIOSpecDefault:

  private val SvgNs  = DomOps.SvgNs
  private val HtmlNs = DomOps.HtmlNs
  private val MathNs = DomOps.MathNs

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  private def nsOf(node: js.Dynamic): String =
    val n = node.namespaceURI
    if n == null || js.isUndefined(n) then "" else n.asInstanceOf[String]

  private def firstChild(parent: dom.Element): js.Dynamic =
    parent.asInstanceOf[js.Dynamic].firstChild

  private def childElements(el: js.Dynamic): List[js.Dynamic] =
    val kids = el.children
    val n    = kids.length.asInstanceOf[Int]
    (0 until n).map(i => kids.item(i).asInstanceOf[js.Dynamic]).toList

  private def findByTag(root: js.Dynamic, tag: String): Option[js.Dynamic] =
    if root.nodeType.asInstanceOf[Int] != 1 then None
    else if root.tagName.asInstanceOf[String].equalsIgnoreCase(tag) then Some(root)
    else childElements(root).flatMap(c => findByTag(c, tag)).headOption

  def spec = suite("Mount SVG / MathML namespaces")(
    test("svg root and SVG-only descendants use the SVG namespace") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          "svg",
          Vector.empty,
          Vector(
            UI.Element(
              "g",
              Vector.empty,
              Vector(
                UI.Element("rect", Vector.empty, Vector.empty),
                UI.Element("text", Vector.empty, Vector(UI.Text("label"))),
              ),
            )
          ),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val svg  = firstChild(parent)
          val g    = findByTag(svg, "g").get
          val rect = findByTag(svg, "rect").get
          val text = findByTag(svg, "text").get
          assertTrue(
            nsOf(svg) == SvgNs,
            nsOf(g) == SvgNs,
            nsOf(rect) == SvgNs,
            nsOf(text) == SvgNs,
          )
        end for
      }
    },
    test("style nested under svg is SVG-namespace (not HTML style)") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          "svg",
          Vector.empty,
          Vector(UI.Element("style", Vector.empty, Vector(UI.Text(".node { fill: #333 }")))),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val style = findByTag(firstChild(parent), "style").get
          assertTrue(nsOf(style) == SvgNs)
      }
    },
    test("style under an HTML div stays in the HTML namespace") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          "div",
          Vector.empty,
          Vector(UI.Element("style", Vector.empty, Vector(UI.Text("body { margin: 0 }")))),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val style = findByTag(firstChild(parent), "style").get
          assertTrue(nsOf(style) == HtmlNs)
      }
    },
    test("foreignObject is SVG; its children switch back to HTML") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          "svg",
          Vector.empty,
          Vector(
            UI.Element(
              "foreignObject",
              Vector.empty,
              Vector(UI.Element("div", Vector.empty, Vector(UI.Text("html inside svg")))),
            )
          ),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val fo  = findByTag(firstChild(parent), "foreignObject").get
          val div = findByTag(fo, "div").get
          assertTrue(nsOf(fo) == SvgNs, nsOf(div) == HtmlNs)
      }
    },
    test("math root uses the MathML namespace") {
      withParent { parent =>
        val ui: UI[Any] = UI.Element(
          "math",
          Vector.empty,
          Vector(UI.Element("mi", Vector.empty, Vector(UI.Text("x")))),
        )
        for _ <- AscentApp.mount(ui, parent)
        yield
          val math = firstChild(parent)
          val mi   = findByTag(math, "mi").get
          assertTrue(nsOf(math) == MathNs, nsOf(mi) == MathNs)
      }
    },
    test("remount into a cleared HTML host keeps SVG namespace (Specular live-example path)") {
      withParent { parent =>
        val diagram: UI[Any] = UI.Element(
          "svg",
          Vector.empty,
          Vector(
            UI.Element("g", Vector.empty, Vector(UI.Element("text", Vector.empty, Vector(UI.Text("MermaidParser")))))
          ),
        )
        for
          cleanup1 <- AscentApp.mount(diagram, parent)
          _        <- cleanup1.cancelAll
          _        <- ZIO.succeed {
            while parent.asInstanceOf[js.Dynamic].firstChild != null do
              parent.removeChild(parent.asInstanceOf[js.Dynamic].firstChild.asInstanceOf[dom.Node])
              ()
          }
          _ <- AscentApp.mount(diagram, parent)
        yield
          val svg  = firstChild(parent)
          val text = findByTag(svg, "text").get
          // Without createElementNS, remounted <text> is HTML-unknown and textContent concatenates
          // across siblings with no geometry — namespaceURI is the precise failure mode.
          assertTrue(nsOf(svg) == SvgNs, nsOf(text) == SvgNs)
        end for
      }
    },
  )
end MountSvgSpec
