package ascent.datastar.js

import ascent.datastar.{Datastar, SignalStore}
import ascent.dom
import zio.*

import scala.scalajs.js

/** Outgoing action dispatch — the client→server half of the loop. ascent owns the DOM, so there are NO `data-on`
  * attributes: a view wires `Ev.onClick(_ => Action.post(store, "/inc"))` and this snapshots the store's signals and
  * `fetch`es them in the shape the SDK's `readSignals` expects.
  *
  * Totally effectful and total (`UIO`): a network/HTTP failure is logged and the effect still succeeds, so it composes
  * straight into a `URIO[R, Unit]` event handler without widening the error channel.
  */
object Action:

  /** POST the current signals as a JSON body (the `@post` action shape). */
  def post(store: SignalStore, url: String): UIO[Unit] =
    store.snapshot.flatMap { signals =>
      val body = Datastar.encodeSignalsBody(signals)
      fetchJson(url, "POST", Some(body))
    }

  /** GET with the current signals URL-encoded into the `datastar` query parameter (the `@get` shape). */
  def get(store: SignalStore, url: String): UIO[Unit] =
    store.snapshot.flatMap { signals =>
      val body = Datastar.encodeSignalsBody(signals)
      val sep  = if url.contains('?') then "&" else "?"
      val full = s"$url$sep${Datastar.SignalsQueryParam}=${encodeURIComponent(body)}"
      fetchJson(full, "GET", None)
    }

  private def fetchJson(url: String, method: String, body: Option[String]): UIO[Unit] =
    val init = js.Dynamic.literal(
      method = method,
      headers = js.Dynamic.literal(
        "Content-Type"            -> "application/json",
        Datastar.RequestHeader._1 -> Datastar.RequestHeader._2,
      ),
    )
    body.foreach(b => init.updateDynamic("body")(b))
    promiseToZio(dom.window.fetch(url, init.asInstanceOf[dom.RequestInit])).unit
      .catchAll(t => ZIO.logWarning(s"datastar action $method $url failed: ${t.getMessage}"))
  end fetchJson

  /** Bridge a JS `Promise` (returned by `fetch`) to a ZIO effect. */
  private def promiseToZio(promise: js.Any): Task[Any] =
    ZIO.async[Any, Throwable, Any] { cb =>
      val thenable = promise.asInstanceOf[js.Dynamic]
      thenable.`then`(
        (value: js.Any) => cb(ZIO.succeed(value)),
        (err: js.Any) => cb(ZIO.fail(js.JavaScriptException(err))),
      )
      ()
    }

  private def encodeURIComponent(s: String): String =
    js.Dynamic.global.encodeURIComponent(s).asInstanceOf[String]
end Action
