package ascent.preview

import zio.*
import zio.http.*

import java.nio.file.Paths

/** CLI: `PreviewMain <port> <siteRoot> [--open]`. Forked by sbt-ascent-preview; also `preview/run` for ad-hoc serving.
  */
object PreviewMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      config = configFromArgs(args)
      _ <- Preview
        .serve(config)
        .provideSome[Scope](Server.defaultWith(_.port(config.port)))
    yield ()

  private[preview] def configFromArgs(args: Chunk[String]): PreviewConfig =
    val open       = args.contains("--open")
    val positional = args.filterNot(_ == "--open")
    val port       = positional.headOption.map(_.toInt).getOrElse(8765)
    val root       = positional.lift(1).map(Paths.get(_)).getOrElse(Paths.get("target/site").toAbsolutePath)
    PreviewConfig(root = root, port = port, openBrowser = open)
end PreviewMain
