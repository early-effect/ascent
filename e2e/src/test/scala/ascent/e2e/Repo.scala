package ascent.e2e

import zio.*
import zio.http.*

import java.nio.file.{Files, Path, Paths}

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
      s"missing staged preview at $dir (e2eStage should have run previewStage)",
    )
    dir

  def fastJs(exampleDir: String): Path =
    preview(exampleDir).resolve("fast.js")

object PreviewServe:
  val serverLayer: ZLayer[Any, Throwable, Server] =
    Server.defaultWith(_.onAnyOpenPort)

  def install(routes: Routes[Any, Response]): ZIO[Server, Throwable, PreviewUrl] =
    Server.install(routes).map(port => PreviewUrl(s"http://127.0.0.1:$port"))
