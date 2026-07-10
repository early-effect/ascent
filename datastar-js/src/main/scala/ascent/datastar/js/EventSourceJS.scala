package ascent.datastar.js

import scala.annotation.nowarn
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

/** A hand-written constructor facade for the browser `EventSource`.
  *
  * The generated `ascent.dom.EventSource` facade is a parameterless `@JSGlobal class` (the domgen generator emits no JS
  * constructors), so `new ascent.dom.EventSource(url)` won't compile. This tiny subclass adds the real constructor; it
  * IS-A `ascent.dom.EventSource`, so all inherited members (`addEventListener`, `close()`, `readyState`, …) work
  * unchanged.
  *
  * The constructor params are facade signatures consumed by the JS runtime, never read in Scala, so `-Wunused` is
  * suppressed here (the same reason generated `@js.native` members are exempt).
  */
@js.native
@JSGlobal("EventSource")
@nowarn("msg=unused explicit parameter")
class EventSourceJS(url: String, init: js.UndefOr[js.Object] = js.undefined) extends ascent.dom.EventSource
