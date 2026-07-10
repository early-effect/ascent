package ascent.datastar

import zio.json.ast.Json

/** The datastar wire-protocol dialect (datastar v1.0.x).
  *
  * Parses the two SSE events a datastar server emits — recognising the `event:` names `datastar-patch-signals` and
  * `datastar-patch-elements` — into ascent's neutral [[RemoteEvent]] model, and formats the outgoing signals a datastar
  * action POSTs.
  *
  * The `data` block this receives is the SSE payload AFTER the browser has stripped the per-line `data: ` prefixes
  * (each `data:` line joined by `\n`), so it parses lines like:
  * {{{
  *   selector #foo
  *   mode inner
  *   useViewTransition true
  *   elements <div>
  *   elements   hello
  *   signals {"count":1}
  *   onlyIfMissing true
  * }}}
  * A `field value` line is split on the FIRST space; multiple `elements` lines re-join with `\n` (the inverse of the
  * SDK's multiline encoding).
  */
object Datastar extends RemoteDialect:

  val PatchSignals  = "datastar-patch-signals"
  val PatchElements = "datastar-patch-elements"

  /** The header a datastar request always carries. */
  val RequestHeader: (String, String) = "Datastar-Request" -> "true"

  /** The query-parameter name carrying signals on a GET action. */
  val SignalsQueryParam = "datastar"

  def eventNames: Set[String] = Set(PatchSignals, PatchElements)

  def parse(eventName: String, data: String): Either[String, RemoteEvent] =
    eventName match
      case PatchSignals  => parsePatchSignals(data)
      case PatchElements => parsePatchElements(data)
      case other         => Left(s"unsupported datastar event: $other")

  /** Split a data block into `(field, value)` lines (value may be empty), splitting each on the first space. Blank
    * lines are dropped.
    */
  private def lines(data: String): Vector[(String, String)] =
    data.linesIterator
      .filter(_.nonEmpty)
      .map { line =>
        val sp = line.indexOf(' ')
        if sp < 0 then (line, "") else (line.substring(0, sp), line.substring(sp + 1))
      }
      .toVector

  private def parsePatchSignals(data: String): Either[String, RemoteEvent] =
    val fs            = lines(data)
    val onlyIfMissing = fs.exists((f, v) => f == "onlyIfMissing" && v.trim == "true")
    // `signals` may technically span multiple data lines (large JSON); re-join them.
    val signalsJson = fs.collect { case ("signals", v) => v }.mkString("\n")
    if signalsJson.isEmpty then Left("patch-signals: missing `signals` line")
    else
      Json.decoder.decodeJson(signalsJson) match
        case Right(obj: Json.Obj) => Right(RemoteEvent.PatchSignals(obj, onlyIfMissing))
        case Right(_)             => Left("patch-signals: `signals` must be a JSON object")
        case Left(err)            => Left(s"patch-signals: invalid JSON ($err)")
  end parsePatchSignals

  private def parsePatchElements(data: String): Either[String, RemoteEvent] =
    val fs       = lines(data)
    val selector = fs.collectFirst { case ("selector", v) => v.trim }.filter(_.nonEmpty)
    val modeTok  = fs.collectFirst { case ("mode", v) => v.trim }
    val useVt    = fs.exists((f, v) => f == "useViewTransition" && v.trim == "true")
    // Multiple `elements` lines re-join with newline (inverse of the SDK's per-line encoding).
    val html = fs.collect { case ("elements", v) => v }.mkString("\n")
    modeTok match
      case Some(tok) =>
        ElementPatchMode.fromWire(tok) match
          case Some(mode) => Right(RemoteEvent.PatchElements(html, selector, mode, useVt))
          case None       => Left(s"patch-elements: unknown mode `$tok`")
      case None =>
        Right(RemoteEvent.PatchElements(html, selector, ElementPatchMode.default, useVt))
  end parsePatchElements

  /** Encode a signals object as the JSON body of a `@post`-style action. */
  def encodeSignalsBody(signals: Json.Obj): String =
    Json.encoder.encodeJson(signals).toString
end Datastar
