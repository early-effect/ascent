package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object GettingStartedSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(GettingStarted).provideLayer(ExampleRunner.live)
