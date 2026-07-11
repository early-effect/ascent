package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object ReactiveBoundariesSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(ReactiveBoundaries).provideLayer(ExampleRunner.live)
