package ascent.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/** Builds the ascent docs site into `<repo>/target/site`. */
object BuildSite extends ZIOAppDefault:

  def run =
    val out  = SitePaths.outDir(repoRoot.resolve("target/site"))
    val base = SitePaths.basePath(".")
    val meta = ProjectMeta.fromSystemProperties
      .map(m =>
        m.copy(
          name = "ascent",
          title = Some("ascent"),
          description = Some(
            m.description.getOrElse(
              "Effect-native reactive UI for Scala 3; direct DOM, Squawk boundaries, ZIO throughout."
            )
          ),
          language = Some("Scala"),
        )
      )
      .orElse(
        Some(
          ProjectMeta(
            name = "ascent",
            organization = "rocks.earlyeffect",
            version = "0.1.0-SNAPSHOT",
            scalaVersion = "3.8.4",
            title = Some("ascent"),
            description = Some(
              "Effect-native reactive UI for Scala 3; direct DOM, Squawk boundaries, ZIO throughout."
            ),
            language = Some("Scala"),
          )
        )
      )
    val version = meta.map(_.version).getOrElse("0.1.0-SNAPSHOT")
    val org     = meta.map(_.organization).getOrElse("rocks.earlyeffect")
    val model   = SiteModel(
      title = "ascent",
      basePath = base,
      pages = Vector(
        GettingStarted.doc,
        SquawkPage.doc,
        Dsl.doc,
        ReactiveBoundaries.doc,
        Css.doc,
        ConduitPage.doc,
        Mounting.doc,
        HtmlPage.doc,
        DatastarPage.doc,
        DatastarHttp.doc,
        Hybrid.doc,
        Modules.doc,
      ),
      clientScript = Some("assets/client.js"),
      meta = meta,
      description = meta.flatMap(_.description),
      logo = Some(EarlyEffectTheme.logoHref),
      logoLink = Some("https://www.earlyeffect.rocks/"),
      summaryMarkdown = Some(
        s"""**ascent** is effect-native reactive UI for **Scala 3**. It renders straight to the DOM:
no virtual DOM, no diffing. The UI is a pure tree built once; the engine surgically patches the
exact node, attribute, or child-list behind each reactive boundary. The substrate is **ZIO**.

Docs pages are Specular `DocSpec`s: the same source asserts under zio-test and SSR-renders here.
"""
      ),
      installSnippets = Vector(
        CodeSnippet(
          "Install (core + browser)",
          s"""libraryDependencies ++= Seq(
  "$org" %%% "ascent-core" % "$version",
  "$org" %%% "ascent-js"   % "$version", // Scala.js mount engine
  "$org" %%% "ascent-css"  % "$version", // optional typed CSS
)""",
        ),
        CodeSnippet(
          "Optional modules",
          s"""libraryDependencies ++= Seq(
  "$org" %%% "ascent-conduit"      % "$version", // Ctx[M] state bridge
  "$org" %%  "ascent-html"         % "$version", // SSR
  "$org" %%  "ascent-datastar-http" % "$version", // server datastar
  "$org" %%% "ascent-datastar-js"  % "$version", // browser datastar
)""",
        ),
      ),
    )
    ZIO
      .serviceWithZIO[SiteBuilder](_.buildSite(model, out))
      .flatMap { result =>
        EarlyEffectTheme.writeLogo(out) *>
          copyClientBundle(out) *>
          Console.printLine(s"Wrote ${result.pages.mkString(", ")}")
      }
      .provide(
        MarkdownRenderer.live,
        ExampleRunner.live,
        HtmlSsr.live,
        SiteWriter.live,
        NavBuilder.live,
        EarlyEffectTheme.live,
        PageTemplate.live,
        LandingTemplate.live,
        SiteBuilder.live,
      )
  end run

  private def copyClientBundle(out: Path): Task[Unit] =
    ZIO.attempt {
      val dest = out.resolve("assets/client.js")
      val src  = findClientJs.getOrElse {
        throw new RuntimeException(
          "JS client not linked; run docs/specularSite (or docsJS/fastLinkJS) first. " +
            s"Looked for marker ${clientJsMarker} and under ${repoRoot.resolve("target/out")}"
        )
      }
      Files.createDirectories(dest.getParent)
      Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  private def clientJsMarker: Path =
    repoRoot.resolve("target/specular-client-js.path")

  private def findClientJs: Option[Path] =
    readMarker.orElse(walkTargetOut)

  private def readMarker: Option[Path] =
    val marker = clientJsMarker
    if !Files.isRegularFile(marker) then None
    else
      val line = Files.readString(marker).nn.trim
      if line.isEmpty then None
      else
        val path = Paths.get(line)
        Option.when(Files.isRegularFile(path))(path)

  private def walkTargetOut: Option[Path] =
    val outRoot = repoRoot.resolve("target/out")
    if !Files.isDirectory(outRoot) then None
    else
      val stream = Files.walk(outRoot)
      try
        val found = stream
          .filter { p =>
            val s = p.toString.replace('\\', '/')
            s.endsWith("ascent-docs-fastopt/main.js") || s.endsWith("main.js") && s.contains("docs")
          }
          .findFirst()
        if found.isPresent then Some(found.get.nn) else None
      finally stream.close()

  private def repoRoot: Path =
    Paths.get("").toAbsolutePath.nn
end BuildSite
