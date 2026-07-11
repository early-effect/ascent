package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object MountingSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Mounting).provideLayer(ExampleRunner.live)
