package ascent.e2e

import heddle.*
import zio.*

import java.nio.file.{Files, Path, Paths}

import scala.util.Using

final case class PreviewUrl(value: String):
  def /(p: String): String =
    val path = p.stripPrefix("/")
    if path.isEmpty then value else s"$value/$path"

object Repo:
  def root: Path =
    Paths.get(sys.props.getOrElse("ascent.repoRoot", ".")).toAbsolutePath.normalize

  def preview(exampleDir: String): Path =
    val dir = root.resolve("example").resolve(exampleDir).resolve("target").resolve("preview")
    require(
      Files.isDirectory(dir),
      s"missing staged preview at $dir (e2eStage should have run ascentPreviewStage)",
    )
    dir

  def fastJs(exampleDir: String): Path =
    preview(exampleDir).resolve("fast.js")

  /** Copy a staged preview so one spec's `dev-stamp` rewrite cannot reload another spec's tab. */
  def copyPreview(exampleDir: String): Task[Path] =
    ZIO.attempt:
      val src  = preview(exampleDir)
      val dest = Files.createTempDirectory(s"ascent-e2e-$exampleDir-")
      Using.resource(Files.walk(src)): walk =>
        walk.forEach: from =>
          val to = dest.resolve(src.relativize(from))
          if Files.isDirectory(from) then Files.createDirectories(to)
          else Files.copy(from, to)
      dest
end Repo

object PreviewServe:
  val serverLayer: ULayer[Server.Config] =
    Server.defaultWith(_.port(0))

  def install(routes: Routes[Any, Response]): ZIO[Server.Config & Scope, Throwable, PreviewUrl] =
    Server
      .install(routes)
      .mapError(e => RuntimeException(e.message))
      .flatMap: server =>
        server.port.map(port => PreviewUrl(s"http://127.0.0.1:$port"))

  /** Per-test scope: halt runs when `use` finishes, before the next test. */
  def withServer[R, E, A](
      routes: Routes[Any, Response]
  )(use: PreviewUrl => ZIO[R, E, A]): ZIO[R & Server.Config, E | Throwable, A] =
    ZIO.scoped(install(routes).flatMap(use))
end PreviewServe
