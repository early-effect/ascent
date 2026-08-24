package ascent.chekhov

import scala.annotation.unused

import chekhov.{ChekhovError, Locator, Page}
import zio.{IO, Trace}

/** JVM interpreter: Ascent handles over Chekhov's remote [[Locator]].
  *
  * Methods live here rather than as `extension (page: Page)` members named `getByTestId` / `getByPlaceholder`. Those
  * names already exist on [[Page]] with a different arity, and `page.getByPlaceholder(text, HtmlTag.input)` is parsed
  * as a two-tuple argument to the existing one-arg method.
  */
object PageHandles:

  type Effect[+A] = IO[ChekhovError, A]

  final class LocatorBackend(locatorOf: String => Locator) extends HandleBackend[Effect]:
    def click(selector: String)(using Trace): Effect[Unit] =
      locatorOf(selector).click
    def fill(selector: String, value: String)(using Trace): Effect[Unit] =
      locatorOf(selector).fill(value)
    def press(selector: String, key: String)(using Trace): Effect[Unit] =
      locatorOf(selector).press(key)
    def innerText(selector: String)(using Trace): Effect[String] =
      locatorOf(selector).innerText
    def textContent(selector: String)(using Trace): Effect[String] =
      locatorOf(selector).textContent
  end LocatorBackend

  def backend(page: Page): LocatorBackend = LocatorBackend(page.locator)

  def getByTestId[K](page: Page, testId: String, @unused tag: K)(using h: TagHandle[K]): h.Handle[Effect] =
    h.wrap(taggedSelector(h.tagName, testId), backend(page))

  def getByPlaceholder[K](page: Page, text: String, @unused tag: K)(using
      h: TagHandle[K]
  ): h.Handle[Effect] =
    h.wrap(placeholderSelector(h.tagName, text), backend(page))

  def input(page: Page, testId: String): InputHandle[Effect] =
    getByTestId(page, testId, HtmlTag.input)

  def button(page: Page, testId: String): ButtonHandle[Effect] =
    getByTestId(page, testId, HtmlTag.button)

  def textarea(page: Page, testId: String): TextAreaHandle[Effect] =
    getByTestId(page, testId, HtmlTag.textarea)

  def select(page: Page, testId: String): SelectHandle[Effect] =
    getByTestId(page, testId, HtmlTag.select)
end PageHandles

extension (page: Page)
  def input(testId: String): InputHandle[PageHandles.Effect]       = PageHandles.input(page, testId)
  def button(testId: String): ButtonHandle[PageHandles.Effect]     = PageHandles.button(page, testId)
  def textarea(testId: String): TextAreaHandle[PageHandles.Effect] =
    PageHandles.textarea(page, testId)
  def select(testId: String): SelectHandle[PageHandles.Effect] = PageHandles.select(page, testId)
end extension
