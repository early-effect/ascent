package ascent.preview

import zio.*

import java.nio.file.Paths

/** CLI: `PreviewMain <port> <siteRoot>`. Used by sbt-reload (`runReloadArgs`) and ad-hoc example serving. */
object PreviewMain extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      port = args.headOption.map(_.toInt).getOrElse(8765)
      root = args
        .lift(1)
        .map(Paths.get(_))
        .getOrElse(Paths.get("target/site").toAbsolutePath)
      _ <- Preview.serveForever(PreviewConfig(root = root, port = port))
    yield ()
end PreviewMain
