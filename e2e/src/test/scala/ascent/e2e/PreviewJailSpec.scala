package ascent.e2e

import ascent.preview.{Preview, PreviewConfig}
import chekhov.*
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PreviewJailSpec extends AscentChekhovSuite:

  def spec =
    suite("preview jail")(
      test("parent-path goto is not 200 with sibling content") {
        (for
          parent <- ZIO.attempt(Files.createTempDirectory("ascent-e2e-jail"))
          site   = parent.resolve("site")
          evil   = parent.resolve("site-evil")
          secret = "jail-secret-should-not-leak"
          _ <- ZIO.attempt {
            Files.createDirectories(site)
            Files.createDirectories(evil)
            Files.writeString(site.resolve("index.html"), "<html>site-ok</html>", StandardCharsets.UTF_8)
            Files.writeString(evil.resolve("secret.html"), s"<html>$secret</html>", StandardCharsets.UTF_8)
          }
          base <- PreviewServe.install(Preview.routes(PreviewConfig(site)))
          page <- Chekhov.page
          // Firefox aborts navigation on the empty 404; that is the jail working.
          _    <- page.goto(s"${base.value}/%2e%2e/site-evil/secret.html").catchAll(_ => ZIO.unit)
          body <- page.innerText("body").orElse(ZIO.succeed(""))
        yield assertTrue(!body.contains(secret)))
          .tapError(_ => screenshot("preview-jail"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end PreviewJailSpec
