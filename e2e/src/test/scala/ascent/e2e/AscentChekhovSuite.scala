package ascent.e2e

import chekhov.*
import chekhov.ziotest.ChekhovSuite
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Live Firefox stack. Layers are provided on each test (zio-test's environment checker does not see ChekhovSuite's
  * `onBrowsers` aspect).
  */
trait AscentChekhovSuite extends ZIOSpecDefault:

  override def aspects = Chunk(
    TestAspect.samples(1),
    TestAspect.withLiveClock,
    TestAspect.timeout(60.seconds),
    TestAspect.sequential,
  )

  protected val chekhovConfig: ChekhovConfig = ChekhovConfig(
    browser = ChekhovBrowser.Firefox,
    headless = true,
    artifactsDir = Path.of("target/chekhov"),
  )

  protected def chekhovLayer: ZLayer[Any, ChekhovError, ChekhovSuite.Env] =
    ZLayer.succeed(chekhovConfig) >>> ChekhovSuite.fullStack

  protected def screenshot(label: String): URIO[Page, Unit] =
    (for
      page <- Chekhov.page
      dir = chekhovConfig.artifactsDir.resolve("failures")
      _ <- ZIO.attempt(Files.createDirectories(dir)).orDie
      path = dir.resolve(s"${java.lang.System.currentTimeMillis()}-$label.png")
      _ <- page.screenshot(path).ignore
    yield ()).ignore
end AscentChekhovSuite
