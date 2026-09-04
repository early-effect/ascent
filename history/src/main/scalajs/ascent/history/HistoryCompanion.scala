package ascent.history

import zio.*

trait HistoryCompanion:

  /** Bind `window.history` plus `popstate` / `hashchange`. Scope close removes the listeners. */
  def browser: URIO[Scope, History] = BrowserHistory.make
