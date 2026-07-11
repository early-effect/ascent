package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object ModulesSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Modules).provideLayer(ExampleRunner.live)
