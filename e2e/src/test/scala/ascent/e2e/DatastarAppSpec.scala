package ascent.e2e

import chekhov.*
import example.server.CounterServer
import heddle.Client
import zio.*
import zio.test.*

object DatastarAppSpec extends AscentChekhovSuite:

  def spec =
    suite("datastar-app")(
      test("preview serves index.html and fast.js"):
        CounterServer.makeState.flatMap: state =>
          PreviewServe.withServer(CounterServer.routes(state, Repo.preview("datastar-app"))): base =>
            for
              page <- Client.get(base.value + "/")
              html <- page.body.utf8
              jsR  <- Client.get(base.value + "/fast.js")
              js   <- jsR.body.utf8
            yield assertTrue(
              page.status.isSuccess,
              html.contains("fast.js"),
              jsR.status.isSuccess,
              js.length > 100,
            )
      ,
      test("increment updates the count over same-origin SSE"):
        CounterServer.makeState.flatMap: state =>
          PreviewServe.withServer(CounterServer.routes(state, Repo.preview("datastar-app"))): base =>
            (for
              page <- Chekhov.page
              _    <- page.goto(base.value + "/")
              n0   <- page.innerText("#count").timeout(5.seconds)
              _    <- page.getByRole(Role.Button, name = Some("increment")).click
              n1   <- page.innerText("#count").repeatUntil(_.trim == "1").timeout(10.seconds)
            yield assertTrue(n0.contains("0"), n1.contains("1"))).tapError(_ => screenshot("datastar-app")),
    ).provideSomeShared[Scope](testLayers) @@ TestAspect.withLiveClock
end DatastarAppSpec
