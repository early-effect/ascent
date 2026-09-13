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
      test("parent-path goto is not 200 with sibling content"):
        ZIO
          .attempt(Files.createTempDirectory("ascent-e2e-jail"))
          .flatMap: parent =>
            val site   = parent.resolve("site")
            val evil   = parent.resolve("site-evil")
            val secret = "jail-secret-should-not-leak"
            val setup  = ZIO.attempt:
              Files.createDirectories(site)
              Files.createDirectories(evil)
              Files.writeString(site.resolve("index.html"), "<html>site-ok</html>", StandardCharsets.UTF_8)
              Files.writeString(evil.resolve("secret.html"), s"<html>$secret</html>", StandardCharsets.UTF_8)
            (setup *> PreviewServe.withServer(Preview.routes(PreviewConfig(site))): base =>
              for
                page <- Chekhov.page
                _    <- page.goto(s"${base.value}/%2e%2e/site-evil/secret.html").catchAll(_ => ZIO.unit)
                body <- page.innerText("body").orElse(ZIO.succeed(""))
              yield assertTrue(!body.contains(secret))).tapError(_ => screenshot("preview-jail"))
    ).provideSomeShared[Scope](testLayers) @@ TestAspect.withLiveClock
end PreviewJailSpec
