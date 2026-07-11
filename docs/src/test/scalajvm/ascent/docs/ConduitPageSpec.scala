package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object ConduitPageSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(ConduitPage).provideLayer(ExampleRunner.live)
