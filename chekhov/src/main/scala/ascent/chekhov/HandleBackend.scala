package ascent.chekhov

import zio.Trace

/** Effectful DOM verbs a handle needs. Shared by the JS live-node backend and the JVM Playwright selector backend.
  * Implementations must not appear in this file (no Chekhov, no `ascent.dom`).
  */
trait HandleBackend[F[_]]:
  def click(selector: String)(using Trace): F[Unit]
  def fill(selector: String, value: String)(using Trace): F[Unit]
  def press(selector: String, key: String)(using Trace): F[Unit]
  def innerText(selector: String)(using Trace): F[String]
  def textContent(selector: String)(using Trace): F[String]
end HandleBackend
