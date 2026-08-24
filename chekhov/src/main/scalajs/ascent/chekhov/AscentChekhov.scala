package ascent.chekhov

import ascent.ast.UI
import ascent.dom
import ascent.js.AscentApp
import chekhov.dom.ChekhovDom
import zio.*

import scala.annotation.unused
import scala.scalajs.js

/** JSEnv mount + typed locators. Replaces `chekhov.ascent.ChekhovAscent.withMounted`. */
object AscentChekhov:

  type Effect[+A] = IO[Throwable, A]

  /** Scoped throwaway iframe root, mount `ui`, run `use`, then unmount via [[ascent.js.Subscriptions.cancelAll]]. */
  def withMounted[R, E, A](
      ui: UI[R]
  )(use: AscentRoot => ZIO[R, E, A])(using Trace): ZIO[R, E | Throwable, A] =
    ChekhovDom.withRoot { sjsRoot =>
      val root = sjsRoot.asInstanceOf[dom.Element]
      ZIO.scoped {
        for
          _ <- ZIO.acquireRelease(AscentApp.mount(ui, root))(_.cancelAll)
          a <- use(AscentRoot(root))
        yield a
      }
    }
end AscentChekhov

/** Search root for an iframe-scoped mount. Always query this element, never parent `document.body`. */
final class AscentRoot(val element: dom.Element):

  def getByTestId(testId: String): EffectHandle =
    ElementHandle(testIdSelector(testId), LiveBackend(this, None))

  def getByTestId[K](testId: String, @unused tag: K)(using
      h: TagHandle[K]
  ): LiveHandles.LiveOf[h.Handle] =
    // Wait on the testid (not `input[data-testid=…]`) so a wrong tag is a
    // mismatch, not a timeout.
    LiveHandles.typed(this, testIdSelector(testId), h)

  def input(testId: String): InputHandle[AscentChekhov.Effect] =
    getByTestId(testId, HtmlTag.input)

  def button(testId: String): ButtonHandle[AscentChekhov.Effect] =
    getByTestId(testId, HtmlTag.button)

  def textarea(testId: String): TextAreaHandle[AscentChekhov.Effect] =
    getByTestId(testId, HtmlTag.textarea)

  def select(testId: String): SelectHandle[AscentChekhov.Effect] =
    getByTestId(testId, HtmlTag.select)

  def getByRole(role: String): EffectHandle =
    ElementHandle(roleSelector(role), LiveBackend(this, None))

  def css(selector: String): EffectHandle =
    ElementHandle(selector, LiveBackend(this, None))

  private[chekhov] def waitFor(
      selector: String,
      timeout: Duration = 5.seconds,
      expectedTag: Option[String] = None,
  )(using Trace): IO[Throwable, dom.Element] =
    def attempt: UIO[Option[dom.Element]] =
      ZIO.succeed(Option(element.querySelector(selector)).filter(el => el != null && !js.isUndefined(el)))

    def loop: IO[Throwable, dom.Element] =
      attempt.flatMap {
        case Some(el) => ZIO.succeed(el)
        case None     => ZIO.sleep(50.millis) *> loop
      }

    val hint = expectedTag.fold(selector)(tag => s"""$tag$selector""")
    loop.timeoutFail(new RuntimeException(s"Timeout waiting for $hint"))(timeout)
  end waitFor
end AscentRoot

object LiveHandles:
  type LiveOf[H[_[_]]] = H[AscentChekhov.Effect]

  def typed[K](root: AscentRoot, selector: String, h: TagHandle[K]): LiveOf[h.Handle] =
    h.wrap(selector, LiveBackend(root, Some(h.tagName)))

/** Live-node backend. `expectedTag` is the catalog tag we must see after `querySelector`. */
final class LiveBackend(root: AscentRoot, expectedTag: Option[String]) extends HandleBackend[AscentChekhov.Effect]:
  def click(selector: String)(using Trace): AscentChekhov.Effect[Unit] =
    resolve(selector).map(el => LiveOps.click(el))

  def fill(selector: String, value: String)(using Trace): AscentChekhov.Effect[Unit] =
    resolve(selector).flatMap(el => LiveOps.fill(el, value, selector))

  def press(selector: String, key: String)(using Trace): AscentChekhov.Effect[Unit] =
    resolve(selector).flatMap(el => LiveOps.press(el, key))

  def innerText(selector: String)(using Trace): AscentChekhov.Effect[String] =
    resolve(selector).map(el => Option(el.asInstanceOf[js.Dynamic].innerText.asInstanceOf[String]).getOrElse(""))

  def textContent(selector: String)(using Trace): AscentChekhov.Effect[String] =
    resolve(selector).map(el => Option(el.textContent).getOrElse(""))

  def resolve(selector: String)(using Trace): AscentChekhov.Effect[dom.Element] =
    root.waitFor(selector, expectedTag = expectedTag).flatMap { el =>
      expectedTag match
        case None      => ZIO.succeed(el)
        case Some(tag) =>
          val actual = el.tagName
          if actual.equalsIgnoreCase(tag) then ZIO.succeed(el)
          else ZIO.fail(new RuntimeException(s"expected ${tag.toUpperCase}, got $actual"))
    }
end LiveBackend

/** JS extras that need the live node. Shared handles stay backend-only. */
type EffectHandle = ElementHandle[AscentChekhov.Effect]

extension [H <: ElementHandle[AscentChekhov.Effect]](handle: H)
  def element(using Trace): AscentChekhov.Effect[dom.Element] =
    handle.backend match
      case live: LiveBackend => live.resolve(handle.selector)
      case _                 => ZIO.fail(new RuntimeException("not a live handle"))

  def focus(using Trace): AscentChekhov.Effect[Unit] =
    element.map(el => LiveOps.focus(el))

  def getAttribute(name: String)(using Trace): AscentChekhov.Effect[String] =
    element.map(el => Option(el.getAttribute(name)).getOrElse(""))

  def disabled(using Trace): AscentChekhov.Effect[Boolean] =
    element.map(el => LiveOps.disabled(el))
end extension

extension (handle: InputHandle[AscentChekhov.Effect])
  def value(using Trace): AscentChekhov.Effect[String] =
    handle.element.map(el => LiveOps.stringProp(el, "value"))

  def checked(using Trace): AscentChekhov.Effect[Boolean] =
    handle.element.map(el => LiveOps.boolProp(el, "checked"))

  def check(using Trace): AscentChekhov.Effect[Unit] =
    handle.element.flatMap(el => LiveOps.setChecked(el, want = true, handle.selector))

  def uncheck(using Trace): AscentChekhov.Effect[Unit] =
    handle.element.flatMap(el => LiveOps.setChecked(el, want = false, handle.selector))

  def selectionStart(using Trace): AscentChekhov.Effect[Int] =
    handle.element.flatMap(el => LiveOps.selectionStart(el, handle.selector))
end extension

extension (handle: TextAreaHandle[AscentChekhov.Effect])
  def value(using Trace): AscentChekhov.Effect[String] =
    handle.element.map(el => LiveOps.stringProp(el, "value"))

  def selectionStart(using Trace): AscentChekhov.Effect[Int] =
    handle.element.flatMap(el => LiveOps.selectionStart(el, handle.selector))
end extension

extension (handle: ButtonHandle[AscentChekhov.Effect])
  def value(using Trace): AscentChekhov.Effect[String] =
    handle.element.map(el => LiveOps.stringProp(el, "value"))

extension (handle: SelectHandle[AscentChekhov.Effect])
  def value(using Trace): AscentChekhov.Effect[String] =
    handle.element.map(el => LiveOps.stringProp(el, "value"))

  def selectOption(value: String)(using Trace): AscentChekhov.Effect[Unit] =
    handle.element.flatMap(el => LiveOps.selectOption(el, value, handle.selector))
end extension

private[chekhov] object LiveOps:
  def click(el: dom.Element): Unit =
    el.asInstanceOf[js.Dynamic].click()
    ()

  def focus(el: dom.Element): Unit =
    el.asInstanceOf[js.Dynamic].focus()
    ()

  def stringProp(el: dom.Element, name: String): String =
    Option(el.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[String]).getOrElse("")

  def boolProp(el: dom.Element, name: String): Boolean =
    el.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[Boolean]

  def disabled(el: dom.Element): Boolean =
    boolProp(el, "disabled")

  def fill(el: dom.Element, value: String, selector: String): IO[Throwable, Unit] =
    val tag = el.tagName
    if tag.equalsIgnoreCase("INPUT") || tag.equalsIgnoreCase("TEXTAREA") then
      ZIO.succeed {
        val dyn = el.asInstanceOf[js.Dynamic]
        dyn.value = value
        dispatchInput(el)
      }
    else ZIO.fail(new RuntimeException(s"not an input: $selector"))

  def setChecked(el: dom.Element, want: Boolean, selector: String): IO[Throwable, Unit] =
    if el.tagName.equalsIgnoreCase("INPUT") then
      ZIO.succeed {
        val dyn = el.asInstanceOf[js.Dynamic]
        dyn.checked = want
        dispatchInput(el)
      }
    else ZIO.fail(new RuntimeException(s"not an input: $selector"))

  def selectionStart(el: dom.Element, selector: String): IO[Throwable, Int] =
    val tag = el.tagName
    if tag.equalsIgnoreCase("INPUT") || tag.equalsIgnoreCase("TEXTAREA") then
      ZIO.succeed(el.asInstanceOf[js.Dynamic].selectionStart.asInstanceOf[Int])
    else ZIO.fail(new RuntimeException(s"not an input: $selector"))

  def selectOption(el: dom.Element, value: String, selector: String): IO[Throwable, Unit] =
    if el.tagName.equalsIgnoreCase("SELECT") then
      ZIO.succeed {
        val dyn = el.asInstanceOf[js.Dynamic]
        dyn.value = value
        dispatchInput(el)
      }
    else ZIO.fail(new RuntimeException(s"not a select: $selector"))

  def press(el: dom.Element, key: String): IO[Throwable, Unit] =
    ZIO.succeed {
      val dyn  = el.asInstanceOf[js.Dynamic]
      val view = eventView(el)
      val init = js.Dynamic.literal(bubbles = true, cancelable = true, key = key)
      val ev   = js.Dynamic.newInstance(view.selectDynamic("KeyboardEvent"))("keydown", init)
      dyn.dispatchEvent(ev)
      ()
    }

  def dispatchInput(el: dom.Element): Unit =
    val view = eventView(el)
    val init = js.Dynamic.literal(bubbles = true)
    val ev   = js.Dynamic.newInstance(view.selectDynamic("InputEvent"))("input", init)
    el.asInstanceOf[js.Dynamic].dispatchEvent(ev)
    ()

  /** Construct events in the iframe's window. Parent-window `InputEvent` is a different realm. */
  def eventView(el: dom.Element): js.Dynamic =
    val doc  = el.asInstanceOf[js.Dynamic].ownerDocument
    val view =
      if doc != null && !js.isUndefined(doc) then doc.selectDynamic("defaultView")
      else js.undefined
    // `js.Dynamic.global` is not a value; fall back through `.window`.
    if view == null || js.isUndefined(view) then js.Dynamic.global.window
    else view.asInstanceOf[js.Dynamic]
end LiveOps
