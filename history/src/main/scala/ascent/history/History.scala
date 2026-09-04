package ascent.history

import ascent.squawk.Squawk
import zio.*

/** A URL session: the current [[Location]] as a Squawk, commit (`push` / `replace`), and travel (`back` / `forward` /
  * `go`).
  *
  * This is the primitive, not a router. It does not match paths, run loaders, or own the UI tree. A location change is
  * a Squawk write; the already-mounted tree patches at reactive boundaries. Matching is `location.map(parse)` plus
  * `when`. Nested chrome is ordinary UI. A later `ascent.router` may hold a History, parse an ADT, and commit through
  * these verbs; it must not remount `AscentApp`.
  *
  * `push` / `replace` are immediate (`UIO[Unit]`). Pending navigations and blockers sit above this type: they decide,
  * then they commit. `Eq[Location]` means observers do not fire when the URL did not change; `push` of the current URL
  * still records a stack entry (browser default).
  *
  * Construction is `URIO[Scope, History]`. Scope close removes backend listeners (browser `popstate` / `hashchange`).
  * The memory backend has none; `ZIOAppDefault.run` already provides a Scope.
  */
trait History:

  def location: Squawk[Location]

  def push(to: Location): UIO[Unit]

  def replace(to: Location): UIO[Unit]

  /** Resolve `rel` against the current location, then [[push]]. */
  def push(rel: String): UIO[Unit] =
    location.get.flatMap(cur => push(cur.resolve(rel)))

  /** Resolve `rel` against the current location, then [[replace]]. */
  def replace(rel: String): UIO[Unit] =
    location.get.flatMap(cur => replace(cur.resolve(rel)))

  def back: UIO[Unit]

  def forward: UIO[Unit]

  def go(delta: Int): UIO[Unit]

  def length: UIO[Int]

end History

object History extends HistoryCompanion:

  /** In-memory stack. Tests, Chekhov / JSEnv, VS Code webviews, and SSR seed from a request URL. */
  def memory(initial: Location = Location.root): URIO[Scope, History] =
    MemoryHistory.make(initial)
