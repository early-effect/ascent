package ascent.e2e

import example.server.CounterServer
import heddle.Client
import zio.*
import zio.test.*

object PreviewFilesSpec extends AscentChekhovSuite:

  def spec =
    suite("preview files")(
      test("GET / and GET /fast.js succeed"):
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
    ).provideSomeShared[Scope](testLayers) @@ TestAspect.withLiveClock
end PreviewFilesSpec
