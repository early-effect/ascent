package ascent.datastar

/** Parses a raw SSE frame (its `event:` name + the concatenated `data:` block) into ascent's neutral [[RemoteEvent]]
  * model. A pluggable SPI so datastar is the default dialect but not the only possible one — a different backend ships
  * its own `RemoteDialect` and the client runtime routes by event name.
  *
  * `parse` is TOTAL: on a malformed frame it returns `Left(reason)` so the transport can log-and-continue rather than
  * tear down the connection.
  */
trait RemoteDialect:
  /** The SSE `event:` names this dialect handles (the client registers a listener per name). */
  def eventNames: Set[String]

  /** Parse one frame's data block for the given event name. */
  def parse(eventName: String, data: String): Either[String, RemoteEvent]
end RemoteDialect
