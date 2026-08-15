package ascent.e2e

import chekhov.*
import example.server.CounterServer
import zio.*
import zio.test.*

object DatastarAppSpec extends AscentChekhovSuite:

  def spec =
    suite("datastar-app")(
      test("increment updates the count over same-origin SSE") {
        (for
          state <- CounterServer.makeState
          base  <- PreviewServe.install(CounterServer.routes(state, Repo.preview("datastar-app")))
          page  <- Chekhov.page
          _     <- page.goto(base.value + "/")
          _     <- Poll.until("count is 0")(page.innerText("#count"))(_.trim == "0")
          _     <- page.getByRole(Role.Button, name = Some("increment")).click
          next  <- Poll.until("count increments")(page.innerText("#count"))(_.trim == "1")
        yield assertTrue(next.trim == "1"))
          .tapError(_ => screenshot("datastar-app"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end DatastarAppSpec
