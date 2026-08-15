package ascent.js

import ascent.dom

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** Browser live-reload client for [[ascent.preview.Preview]]'s SSE endpoint.
  *
  * Localhost only: a published site must not open EventSource against a missing `/__ascent/reload`. Missing endpoints
  * are inert (the source is closed on the first error; EventSource's default reconnect is not left running).
  */
object DevReload:

  private[js] def isLocalHost(hostname: String): Boolean =
    hostname == "localhost" || hostname == "127.0.0.1" || hostname == "[::1]"

  /** Subscribe to `url` (default `/__ascent/reload`) and call `location.reload()` on the first message. */
  def install(url: String = "/__ascent/reload"): Unit =
    val host =
      try Option(dom.window.location).map(_.hostname).getOrElse("")
      catch case _: Throwable => ""
    if isLocalHost(host) then
      try
        val es       = new DevReloadEventSource(url)
        val onReload = (_: dom.Event) => dom.window.location.reload()
        es.addEventListener("message", onReload)
        es.addEventListener("reload", onReload)
        es.addEventListener("error", (_: dom.Event) => es.close())
      catch case _: Throwable => ()
  end install
end DevReload

@js.native
@JSGlobal("EventSource")
@nowarn("msg=unused explicit parameter")
private[js] class DevReloadEventSource(url: String, init: js.UndefOr[js.Object] = js.undefined)
    extends ascent.dom.EventSource
