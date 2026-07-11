package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object DatastarSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(DatastarPage).provideLayer(ExampleRunner.live)
