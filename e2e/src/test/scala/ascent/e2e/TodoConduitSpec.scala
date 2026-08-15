package ascent.e2e

import ascent.preview.{Preview, PreviewConfig}
import chekhov.*
import zio.*
import zio.test.*

object TodoConduitSpec extends AscentChekhovSuite:

  def spec =
    suite("todo-conduit")(
      test("add, toggle, filter, and clear completed") {
        (for
          base <- PreviewServe.install(Preview.routes(PreviewConfig(Repo.preview("todo-conduit"))))
          page <- Chekhov.page
          _    <- page.goto(base.value + "/")
          box = page.getByPlaceholder("What needs to be done? (press / to focus)")
          _    <- box.fill("buy milk")
          _    <- box.press("Enter")
          list <- Poll.until("todo appears")(page.innerText("body"))(_.contains("buy milk"))
          _    <- page.getByRole(Role.Checkbox).click
          _    <- page.getByRole(Role.Button, name = Some("Show only active todos")).click
          _    <- Poll.until("active filter hides completed")(page.innerText("body"))(!_.contains("buy milk"))
          _    <- page.getByRole(Role.Button, name = Some("Show all todos")).click
          _    <- Poll.until("all filter shows completed")(page.innerText("body"))(_.contains("buy milk"))
          _    <- page.getByRole(Role.Button, name = Some("Permanently delete every completed todo")).click
          _    <- Poll.until("clear completed")(page.innerText("body"))(!_.contains("buy milk"))
        yield assertTrue(list.contains("buy milk")))
          .tapError(_ => screenshot("todo-conduit"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end TodoConduitSpec
