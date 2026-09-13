package ascent.datastar.http

import ascent.ast.UI
import ascent.datastar.ElementPatchMode as AscentMode
import ascent.html.Html
import heddle.datastar.{Datastar, ElementPatchMode as SdkMode, PatchElementOptions, ServerSentEventGenerator}
import zio.*
import zio.json.JsonEncoder

/** Server-side bridge that makes a heddle server "an ascent client": render an ascent `UI` subtree to HTML via
  * [[ascent.html.Html]] and push it through heddle's Datastar SSE generator as a granular `patch-elements`, or push
  * typed `patch-signals`.
  *
  * Authoring stays in ascent's typed DSL; heddle owns the SSE transport.
  */
object AscentDatastar:

  def patch[R](
      ui: UI[R],
      selector: String,
      mode: AscentMode = AscentMode.Outer,
  ): ZIO[R & Datastar, Nothing, Unit] =
    Html.render(ui).flatMap { html =>
      ServerSentEventGenerator.patchElements(
        html,
        PatchElementOptions(selector = Some(selector), mode = toSdkMode(mode)),
      )
    }

  def patch[R](ui: UI[R]): ZIO[R & Datastar, Nothing, Unit] =
    Html.render(ui).flatMap(html => ServerSentEventGenerator.patchElements(html))

  def patchRegion[R](
      id: String,
      ui: UI[R],
      mode: AscentMode = AscentMode.Inner,
  ): ZIO[R & Datastar, Nothing, Unit] =
    patch(ui, s"#$id", mode)

  def patchSignal[A](name: String, value: A)(using enc: JsonEncoder[A]): ZIO[Datastar, Nothing, Unit] =
    ServerSentEventGenerator.patchSignals(s"""{${JsonEncoder.string.encodeJson(name)}:${enc.encodeJson(value)}}""")

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
