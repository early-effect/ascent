package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object SquawkPageSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(SquawkPage).provideLayer(ExampleRunner.live)
