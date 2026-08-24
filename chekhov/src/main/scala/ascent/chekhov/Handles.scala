package ascent.chekhov

import zio.Trace

/** Untyped element. `fill` is deliberately absent: a button or span must not compile a fill. */
class ElementHandle[F[_]](val selector: String, val backend: HandleBackend[F]):
  def click(using Trace): F[Unit]              = backend.click(selector)
  def press(key: String)(using Trace): F[Unit] = backend.press(selector, key)
  def innerText(using Trace): F[String]        = backend.innerText(selector)
  def textContent(using Trace): F[String]      = backend.textContent(selector)

/** `<input>` — the only shared extra verb is [[fill]]. Live `value` / `checked` live on the JS row. */
final class InputHandle[F[_]](selector: String, backend: HandleBackend[F]) extends ElementHandle[F](selector, backend):
  def fill(value: String)(using Trace): F[Unit] = backend.fill(selector, value)

/** `<textarea>` — same shared verbs as [[InputHandle]]. */
final class TextAreaHandle[F[_]](selector: String, backend: HandleBackend[F])
    extends ElementHandle[F](selector, backend):
  def fill(value: String)(using Trace): F[Unit] = backend.fill(selector, value)

/** `<button>` — no `fill`. */
final class ButtonHandle[F[_]](selector: String, backend: HandleBackend[F]) extends ElementHandle[F](selector, backend)

/** `<select>` — `selectOption` is JS-only until Chekhov's `Locator` grows it. */
final class SelectHandle[F[_]](selector: String, backend: HandleBackend[F]) extends ElementHandle[F](selector, backend)
