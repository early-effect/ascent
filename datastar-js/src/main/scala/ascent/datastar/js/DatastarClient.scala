package ascent.datastar.js

import ascent.datastar.{Datastar, RemoteDialect, RemoteEvent, SignalPatch, SignalStore}
import ascent.dom
import zio.*

import scala.scalajs.js

/** The browser-side datastar-protocol runtime: ascent's own Mount + Squawk engine driving the DOM in place of
  * `datastar.js`. Opens an `EventSource`, routes incoming events into the [[SignalStore]] (signals → `Source.set` →
  * ascent boundaries repaint) and into the DOM (patch-elements → selector + mode), and ties teardown to the ambient
  * [[zio.Scope]].
  *
  * Mirrors [[ascent.js.Dom.listen]]: capture `Runtime[R]` once, wire each browser callback with `runOrFork` (sync
  * prefix inline, suspending tail forked), surface a sync defect, let the runtime log a forked one, and
  * `acquireRelease` the connection so `close()` runs when the scope closes.
  */
object DatastarClient:

  /** Open a connection to `url` and route its frames until the ambient scope closes.
    *
    *   - `store` receives `patch-signals` (decoded, RFC-7386-merged, Eq-deduped).
    *   - `patch-elements` are applied to the DOM by selector + mode (direct modes in v1; a real morph fast-follows in
    *     Phase 3b).
    *   - `dialects` defaults to [[Datastar]]; a different backend supplies its own.
    */
  def connect[R](
      url: String,
      store: SignalStore,
      dialects: Vector[RemoteDialect] = Vector(Datastar),
  ): URIO[R & Scope, Unit] =
    for
      runtime <- ZIO.runtime[R]
      es      <- ZIO.acquireRelease(ZIO.succeed(new EventSourceJS(url)))(e => ZIO.succeed(e.close()))
      _       <- ZIO.foreachDiscard(dialects.flatMap(d => d.eventNames.map(_ -> d))) { (eventName, dialect) =>
        registerListener(es, eventName, dialect, store, runtime)
      }
    yield ()

  /** Apply one decoded [[RemoteEvent]] — the pure-of-DOM-effect routing the listener performs. Exposed
    * (package-private) so tests can drive it directly without a live EventSource.
    */
  private[js] def applyEvent(event: RemoteEvent, store: SignalStore): UIO[Unit] =
    event match
      case RemoteEvent.PatchSignals(signals, onlyIfMissing) =>
        store.routeAll(SignalPatch.fromSignals(signals, onlyIfMissing))
      case pe: RemoteEvent.PatchElements =>
        ZIO.succeed(ElementPatching.apply(pe))

  private def registerListener[R](
      es: dom.EventSource,
      eventName: String,
      dialect: RemoteDialect,
      store: SignalStore,
      runtime: Runtime[R],
  ): UIO[Unit] =
    ZIO.succeed {
      val listener: js.Function1[dom.Event, Unit] = (raw: dom.Event) =>
        // MessageEvent doesn't extend Event in our facade; cast as DomEvent handlers do.
        val data = raw.asInstanceOf[dom.MessageEvent].data.asInstanceOf[String]
        val eff  = dialect.parse(eventName, data) match
          case Right(event) => applyEvent(event, store)
          case Left(reason) => ZIO.logWarning(s"datastar: dropped malformed `$eventName` frame: $reason")
        Unsafe.unsafe { implicit u =>
          runtime.unsafe.runOrFork(eff) match
            case Right(exit) => exit.getOrThrowFiberFailure()
            case Left(_)     => ()
        }
        ()
      es.addEventListener(eventName, listener)
    }
end DatastarClient
