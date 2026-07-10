package ascent.js

/** A single, pluggable sink for "you tried something nonsensical" signals from the server-region machinery — so the
  * client side and the server-driving side report violations consistently, and the app decides whether a violation
  * warns (default), throws, or is captured in a test.
  *
  * Defaults to `System.err` (cross-platform — Scala.js maps it to `console.error`, JVM/native to stderr). Swap via
  * [[setHandler]] (e.g. `throw` in development to fail fast, or a recording handler in tests).
  */
object Diagnostics:

  /** The kinds of nonsensical action the server-region contract guards against. */
  enum Violation derives CanEqual:
    /** The server tried to patch elements addressed to an id that is not currently a live server region. */
    case PatchTargetMissing(id: String, status: ServerRegionRegistry.Status)

    /** Client code tried to mutate DOM that lives inside a server-owned region. */
    case ClientMutatedServerRegion(id: String)

    /** Two server regions were mounted with the same id. */
    case DuplicateRegion(id: String)

    /** A server patch arrived without a selector (the protocol's id-fallback isn't supported here). */
    case PatchMissingSelector
  end Violation

  private var handler: (Violation, String) => Unit = (_, msg) => defaultWarn(msg)

  /** Replace the violation handler. Returns the previous one (handy for scoped overrides / test setup). */
  def setHandler(h: (Violation, String) => Unit): (Violation, String) => Unit =
    val prev = handler
    handler = h
    prev

  /** Restore the default warning handler. */
  def resetHandler(): Unit =
    handler = (_, msg) => defaultWarn(msg)

  /** A handler that throws — fail-fast in development. */
  val throwing: (Violation, String) => Unit = (_, msg) => throw new IllegalStateException(s"ascent: $msg")

  /** Route a violation through the active handler. Public so the server-driving runtime (`ascent-datastar-js`) and app
    * code can report contract violations consistently.
    */
  def report(v: Violation, message: String): Unit =
    handler(v, message)

  /** Cross-platform warning: `System.err`. On Scala.js this is mapped to `console.error`; on JVM/native it's stderr. */
  private def defaultWarn(message: String): Unit =
    System.err.println(s"ascent: $message")
end Diagnostics
