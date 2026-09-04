package ascent.history

import ascent.dom
import ascent.squawk.{Source, sq}
import zio.*

import scala.scalajs.js

/** Thin wrapper over `window.history`. Travel (`back` / `forward` / `go`) is the browser's; the Squawk is written on
  * `popstate` / `hashchange` (and immediately on `push` / `replace`, because `pushState` does not fire `popstate`).
  */
private final class BrowserHistory(win: dom.Window, source: Source[Location]) extends History:

  def location: ascent.squawk.Squawk[Location] = source

  def push(to: Location): UIO[Unit] =
    ZIO.succeed(win.history.pushState(null: js.Any, "", to.href)) *> source.set(to)

  def replace(to: Location): UIO[Unit] =
    ZIO.succeed(win.history.replaceState(null: js.Any, "", to.href)) *> source.set(to)

  def back: UIO[Unit] = travel(win.history.back())

  def forward: UIO[Unit] = travel(win.history.forward())

  def go(delta: Int): UIO[Unit] = travel(win.history.go(delta))

  /** `history.back()` is sync in jsdom and async in browsers. Snapshot after the call so a sync backend updates
    * immediately; a later `popstate` of the same URL is an Eq no-op.
    */
  private def travel(act: => Unit): UIO[Unit] =
    ZIO.succeed(act) *> source.set(BrowserHistory.fromWindow(win))

  def length: UIO[Int] = ZIO.succeed(win.history.length)

end BrowserHistory

private[history] object BrowserHistory:

  def make: URIO[Scope, History] = make(dom.window)

  def make(win: dom.Window): URIO[Scope, History] =
    for
      runtime <- ZIO.runtime[Any]
      source  <- sq(fromWindow(win))
      listener: js.Function1[dom.Event, Unit] = (_: dom.Event) =>
        Unsafe.unsafe { implicit u =>
          val _ = runtime.unsafe.run(source.set(fromWindow(win)))
        }
      _ <- ZIO.acquireRelease(
        ZIO.succeed {
          win.addEventListener("popstate", listener)
          win.addEventListener("hashchange", listener)
        }
      )(_ =>
        ZIO.succeed {
          win.removeEventListener("popstate", listener)
          win.removeEventListener("hashchange", listener)
        }
      )
    yield BrowserHistory(win, source)

  private[history] def fromWindow(win: dom.Window): Location =
    val loc = win.location.asInstanceOf[js.Dynamic]
    Location(jsStr(loc, "pathname"), jsStr(loc, "search"), jsStr(loc, "hash"))

  /** `js.native` String fields are `undefined` in some jsdom snapshots; touching them as `String` is a CCE. */
  private def jsStr(d: js.Dynamic, name: String): String =
    val v = d.selectDynamic(name)
    if js.isUndefined(v) || v == null then "" else v.toString

end BrowserHistory
