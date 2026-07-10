package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.ElementPatchMode as AscentMode
import ascent.html.Html
import zio.*
import zio.http.datastar.{Datastar, ElementPatchMode as SdkMode, PatchElementOptions, ServerSentEventGenerator}
import zio.http.template2.CssSelector
import zio.json.JsonEncoder

/** Server-side bridge that makes a zio-http server "an ascent client": render an ascent `UI` subtree to HTML via
  * [[ascent.html.Html]] and push it through the official `zio-http-datastar-sdk`'s `ServerSentEventGenerator` as a
  * granular `patch-elements`, or push typed `patch-signals`.
  *
  * Authoring stays in ascent's typed DSL; the SDK owns the SSE transport, signal reading, and (via zio-http)
  * compression. A datastar user keeps the SDK's `events{}` / `req.readSignals` idiom and only swaps `template2` for
  * ascent views.
  */
object AscentDatastar:

  /** Render `ui` and push it as a `patch-elements` targeting `selector` with `mode` (default the protocol's `outer`).
    * The client morphs it into place.
    */
  def patch[R](
      ui: UI[R],
      selector: String,
      mode: AscentMode = AscentMode.Outer,
  ): ZIO[R & Datastar, Nothing, Unit] =
    Html.render(ui).flatMap { html =>
      ServerSentEventGenerator.patchElements(
        html,
        PatchElementOptions(selector = Some(CssSelector.raw(selector)), mode = toSdkMode(mode)),
      )
    }

  /** Render `ui` with `outer` mode and the protocol's id-fallback (no explicit selector — the client targets the
    * element's own id, which the rendered HTML must carry).
    */
  def patch[R](ui: UI[R]): ZIO[R & Datastar, Nothing, Unit] =
    Html.render(ui).flatMap(html => ServerSentEventGenerator.patchElements(html))

  /** Render `ui` and patch it into a server region the CLIENT declared via `serverRegion(id)`. Targets `#id` with
    * `inner` mode by default (replace the region's interior); the id is the exact address the client mounted, so the
    * two sides agree by construction. This is the idiomatic way to drive a server-owned region from the server.
    */
  def patchRegion[R](
      id: String,
      ui: UI[R],
      mode: AscentMode = AscentMode.Inner,
  ): ZIO[R & Datastar, Nothing, Unit] =
    patch(ui, s"#$id", mode)

  /** Push a single named signal as a `patch-signals` carrying `{name: value}` (value JSON-encoded). */
  def patchSignal[A](name: String, value: A)(using enc: JsonEncoder[A]): ZIO[Datastar, Nothing, Unit] =
    ServerSentEventGenerator.patchSignals(s"""{${JsonEncoder.string.encodeJson(name)}:${enc.encodeJson(value)}}""")

  /** Push a raw signals JSON object (`{...}`) as a `patch-signals`. */
  def patchSignalsJson(signalsJson: String): ZIO[Datastar, Nothing, Unit] =
    ServerSentEventGenerator.patchSignals(signalsJson)

  private def toSdkMode(mode: AscentMode): SdkMode =
    mode match
      case AscentMode.Outer   => SdkMode.Outer
      case AscentMode.Inner   => SdkMode.Inner
      case AscentMode.Replace => SdkMode.Replace
      case AscentMode.Append  => SdkMode.Append
      case AscentMode.Prepend => SdkMode.Prepend
      case AscentMode.Before  => SdkMode.Before
      case AscentMode.After   => SdkMode.After
      case AscentMode.Remove  => SdkMode.Remove
end AscentDatastar
