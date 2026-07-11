package ascent.docs

import specular.*
import zio.test.*

/** Guards that teaching examples publish supporting definitions in the site source panel. */
object DocsSourceSpec extends ZIOSpecDefault:

  def spec = suite("Docs source panels")(
    test("CSS examples include CssClass definitions") {
      val ui = collectUi(Css.doc.children).filter(_.source.contains("extends CssClass"))
      assertTrue(ui.exists(_.source.contains("object Card")))
    },
    test("forEachSignal page documents Row (md fence; local case classes cannot splice)") {
      val md = collectMd(ReactiveBoundaries.doc.children)
      val ui = collectUi(ReactiveBoundaries.doc.children)
      assertTrue(
        md.exists(_.contains("case class Row")),
        ui.exists(_.source.contains("forEachSignal")),
      )
    },
    test("Datastar HTTP live server inlines routes") {
      val zs = collectValues(DatastarHttp.doc.children)
      assertTrue(
        zs.exists(ex => ex.source.contains("Method.POST") && ex.source.contains("increment")),
        !zs.exists(_.source.contains("DocServers")),
      )
    },
    test("Hybrid live server inlines routes") {
      val zs = collectValues(Hybrid.doc.children)
      assertTrue(
        zs.exists(ex => ex.source.contains("patchRegion") && ex.source.contains("Method.POST")),
        !zs.exists(_.source.contains("DocServers")),
      )
    },
  )

  private def collectUi(nodes: Vector[DocNode]): Vector[Example[?]] =
    nodes.flatMap {
      case ex: Example[?]   => Vector(ex)
      case Section(_, kids) => collectUi(kids)
      case _                => Vector.empty
    }

  private def collectValues(nodes: Vector[DocNode]): Vector[ValueExample[?]] =
    nodes.flatMap {
      case ex: ValueExample[?] => Vector(ex)
      case Section(_, kids)    => collectValues(kids)
      case _                   => Vector.empty
    }

  private def collectMd(nodes: Vector[DocNode]): Vector[String] =
    nodes.flatMap {
      case Prose(text)      => Vector(text)
      case Section(_, kids) => collectMd(kids)
      case _                => Vector.empty
    }
end DocsSourceSpec
