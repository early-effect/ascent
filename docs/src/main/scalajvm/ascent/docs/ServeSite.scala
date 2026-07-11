package ascent.docs

import specular.site.SiteServer
import zio.*

import java.nio.file.Paths

/** Preview server entry: `ServeSite <port> <siteRoot>`. */
object ServeSite extends ZIOAppDefault:

  def run =
    for
      args <- getArgs
      port = args.headOption.map(_.toInt).getOrElse(8765)
      root = args
        .lift(1)
        .map(Paths.get(_))
        .getOrElse(Paths.get("target/site").toAbsolutePath)
      _ <- SiteServer.serveForever(root, port)
    yield ()
end ServeSite
