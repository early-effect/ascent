package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object DatastarHttpSpec extends ZIOSpecDefault:
  // Client.request bounds the read with Clock. TestClock never advances that timeout.
  def spec =
    DocTestInterpreter.specOf(DatastarHttp).provideLayer(ExampleRunner.live) @@ TestAspect.withLiveClock
