package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object HtmlPageSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(HtmlPage).provideLayer(ExampleRunner.live)
