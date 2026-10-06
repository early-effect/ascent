package ascent.docs

import specular.*
import zio.test.*

/** Install fences name each artifact's own Central release, and the site does not invent one version. */
object VersionSpec extends ZIOSpecDefault:

  def spec = suite("docs versions")(
    test("getting started quotes Released and not 0.3.0") {
      val md = prose(GettingStarted.doc.children)
      assertTrue(
        md.contains(Released.core),
        md.contains(Released.js),
        md.contains(Released.css),
        !md.contains("0.3.0"),
      )
    },
    test("preview quotes the preview release and not 0.3.0") {
      val md = prose(PreviewPage.doc.children)
      assertTrue(md.contains(Released.preview), !md.contains("0.3.0"))
    },
    test("modules table quotes every row, including internals") {
      val md = prose(Modules.doc.children)
      assertTrue(
        md.contains(Released.core),
        md.contains(Released.js),
        md.contains(Released.dom),
        md.contains(Released.mountEngine),
        md.contains(Released.preview),
        !md.contains("0.3.0"),
      )
    },
    test("index snippets keep each artifact's own number") {
      val versions = ModuleVersions.released.copy(core = "1.2.3", js = "9.8.7")
      val code     = Install.snippets("rocks.earlyeffect", versions).map(_.code).mkString("\n")
      assertTrue(
        code.contains(""""ascent-core" % "1.2.3""""),
        code.contains(""""ascent-js"   % "9.8.7""""),
        code.contains(s""""${versions.chekhov}" % Test"""),
        !code.contains("0.3.0"),
      )
    },
    test("metadata has no single version and points the hub at the docs") {
      val meta = DocsMeta.project
      assertTrue(
        meta.version.isEmpty,
        meta.docsUrl.contains(DocsMeta.DocsUrl),
        meta.homepage.contains(DocsMeta.DocsUrl),
        meta.versionBadge.isEmpty,
      )
    },
  )

  private def prose(nodes: Vector[DocNode]): String =
    nodes
      .flatMap {
        case Prose(text)      => Vector(text)
        case Section(_, kids) => Vector(prose(kids))
        case _                => Vector.empty
      }
      .mkString("\n")
end VersionSpec
