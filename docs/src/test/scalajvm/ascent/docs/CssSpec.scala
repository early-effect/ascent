package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object CssSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(Css).provideLayer(ExampleRunner.live)
