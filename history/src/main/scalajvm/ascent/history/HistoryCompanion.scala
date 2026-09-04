package ascent.history

/** JVM has no `window.history`. [[History.memory]] is the backend; seed it from the request URL for SSR. */
trait HistoryCompanion
