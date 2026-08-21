package ascent.e2e

import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

object SpliceOutputSpec extends ZIOSpecDefault:

  def spec = suite("splice output")(
    test("each example ascentPreviewStage emits one fast.js without relative linker chunks") {
      val examples = Seq("todo-conduit", "datastar-app", "hybrid-chat")
      val results  = examples.map { name =>
        val js = Repo.fastJs(name)
        assertTrue(
          Files.isRegularFile(js),
          !Files.readString(js, StandardCharsets.UTF_8).contains("import \"./"),
        )
      }
      ZIO.succeed(results.reduce(_ && _))
    }
  )
end SpliceOutputSpec
