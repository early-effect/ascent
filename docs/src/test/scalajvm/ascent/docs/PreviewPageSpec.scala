package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object PreviewPageSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(PreviewPage).provideLayer(ExampleRunner.live)
