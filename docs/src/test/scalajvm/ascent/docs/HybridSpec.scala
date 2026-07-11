package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object HybridSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Hybrid).provideLayer(ExampleRunner.live)
