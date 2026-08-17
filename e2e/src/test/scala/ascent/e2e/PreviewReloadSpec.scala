package ascent.e2e

import ascent.preview.{Preview, PreviewConfig}
import chekhov.*
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object PreviewReloadSpec extends AscentChekhovSuite:

  def spec =
    suite("preview reload")(
      test("stamp rewrite reloads the tab; silent subscribe does not") {
        (for
          root <- Repo.copyPreview("todo-conduit")
          stamp = root.resolve("assets/dev-stamp")
          base <- PreviewServe.install(Preview.routes(PreviewConfig(root)))
          page <- Chekhov.page
          _    <- page.goto(base.value + "/")
          _    <- Poll.until("app mounted")(page.innerText("body"))(_.nonEmpty)
          _    <- page.evaluate(
            "() => { window.__ascentLoadId = 'keep'; return window.__ascentLoadId }",
            isFunction = true,
          )
          still <- page.evaluate("() => window.__ascentLoadId", isFunction = true).delay(400.millis)
          _     <- ZIO.attempt(
            Files.writeString(
              stamp,
              java.lang.Long.toString(java.lang.System.currentTimeMillis),
              StandardCharsets.UTF_8,
            )
          )
          gone <- Poll.until("tab reloaded")(page.evaluate("() => window.__ascentLoadId", isFunction = true))(json =>
            !json.contains("keep")
          )
        yield assertTrue(still.contains("keep"), !gone.contains("keep")))
          .tapError(_ => screenshot("preview-reload"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end PreviewReloadSpec
