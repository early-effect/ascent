package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object DatastarHttpSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(DatastarHttp).provideLayer(ExampleRunner.live)
