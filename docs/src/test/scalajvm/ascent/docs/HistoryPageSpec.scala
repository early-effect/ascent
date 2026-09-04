package ascent.docs

import specular.*
import specular.ziotest.DocTestInterpreter
import zio.test.*

object HistoryPageSpec extends ZIOSpecDefault:
  def spec =
    DocTestInterpreter.specOf(HistoryPage).provideLayer(ExampleRunner.live)
