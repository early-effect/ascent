package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object DslSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Dsl).provideLayer(ExampleRunner.live)
