package example.mcphost

import heddle.{/, Endpoint, Schema}
import heddle.mcp.apps.{Grant, Shed, UiUri}
import zio.json.JsonCodec

final case class Count(value: Int) derives Schema, JsonCodec

/** The counter's shed, which the server and its view share: `show_counter` opens the view, and the view may call `inc`.
  */
object Counter:
  val show = Endpoint.get("counter").out[Count].name("show_counter")
  val inc  = Endpoint.post("counter" / "inc").out[Count].name("inc")
  val shed = Shed(UiUri("ui://counter/view"), "Counter", Grant.launch(show))((inc = Grant.app(inc)))
