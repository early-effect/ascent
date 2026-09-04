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
          _    <- settle(page)
          box = page.getByPlaceholder("What needs to be done? (press / to focus)")
          _    <- box.fill("buy milk")
          _    <- box.press("Enter")
          list <- Poll.until("todo appears")(page.innerText("body"))(_.contains("buy milk"))
          _    <- jsClick(page, "input.toggle")
          _    <- jsClick(page, """a[href="#/active"]""")
          _    <- Poll.until("active filter hides completed")(page.innerText("body"))(!_.contains("buy milk"))
          _    <- Poll.until("hash is active")(
            page.evaluate("""() => location.hash""", isFunction = true)
          )(_.contains("active"))
          _ <- page.evaluate("""() => { history.back(); return location.hash; }""", isFunction = true)
          _ <- Poll.until("Back restores all filter")(page.innerText("body"))(_.contains("buy milk"))
          _ <- jsClick(page, """a[href="#/"]""")
          _ <- Poll.until("all filter shows completed")(page.innerText("body"))(_.contains("buy milk"))
          _ <- Poll.until("clear completed is mounted")(
            page.evaluate(
              """() => String(!!document.querySelector('button[aria-label="Permanently delete every completed todo"]'))""",
              isFunction = true,
            )
          )(_.contains("true"))
          _ <- jsClick(page, """button[aria-label="Permanently delete every completed todo"]""")
          _ <- Poll.until("clear completed")(page.innerText("body"))(!_.contains("buy milk"))
        yield assertTrue(list.contains("buy milk")))
          .tapError(_ => screenshot("todo-conduit"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end TodoConduitSpec
