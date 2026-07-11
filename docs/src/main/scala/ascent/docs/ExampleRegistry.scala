package ascent.docs

import ascent.ast.UI
import specular.*
import zio.*

/** Collects interactive example bodies keyed by stable SSR ids (`<page-slug>-ex-N`). */
object ExampleRegistry:

  def fromPages(pages: DocPage*): Map[String, URIO[Scope, UI[Any]]] =
    pages.toVector.flatMap(p => collect(p.children)).toMap

  private def collect(nodes: Vector[DocNode]): Vector[(String, URIO[Scope, UI[Any]])] =
    nodes.flatMap {
      case ex: Example[?] if ex.isInteractive =>
        val erased = ex.asInstanceOf[Example[Any]]
        Vector(erased.id -> erased.body)
      case Section(_, kids) =>
        collect(kids)
      case _ =>
        Vector.empty
    }
end ExampleRegistry
