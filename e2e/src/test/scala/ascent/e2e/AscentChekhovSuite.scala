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

  /** Kill CSS motion so Playwright click is not blocked by fill-mode fade-ins or the looping title glow. */
  protected def settle(page: Page): IO[ChekhovError, Unit] =
    page
      .evaluate(
        """() => {
          const s = document.createElement('style');
          s.setAttribute('data-ascent-e2e', 'no-motion');
          s.textContent = '*, *::before, *::after { animation: none !important; transition: none !important; }';
          document.documentElement.appendChild(s);
          return document.fonts ? document.fonts.ready.then(() => 'ok') : 'ok';
        }""",
        isFunction = true,
      )
      .unit

  /** DOM `.click()`. Skips Playwright actionability (hover tooltips, fill-mode opacity, off-screen footer). */
  protected def jsClick(page: Page, selector: String): IO[ChekhovError, Unit] =
    val json = "\"" + selector.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    page
      .evaluate(
        s"""() => {
          const el = document.querySelector($json);
          if (el == null) throw new Error('missing ' + $json);
          el.click();
          return 'ok';
        }""",
        isFunction = true,
      )
      .unit
  end jsClick

  protected def screenshot(label: String): URIO[Page, Unit] =
    (for
      page <- Chekhov.page
      dir = chekhovConfig.artifactsDir.resolve("failures")
      _ <- ZIO.attempt(Files.createDirectories(dir)).orDie
      path = dir.resolve(s"${java.lang.System.currentTimeMillis()}-$label.png")
      _ <- page.screenshot(path).ignore
    yield ()).ignore
end AscentChekhovSuite
