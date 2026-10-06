package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object HybridSpec extends ZIOSpecDefault:
  // Client.request bounds the read with Clock. TestClock never advances that timeout.
  def spec =
    DocTestInterpreter.specOf(Hybrid).provideLayer(ExampleRunner.live) @@ TestAspect.withLiveClock
