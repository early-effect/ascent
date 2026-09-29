package ascent.js

import ascent.ast.{AscentEvent, Attr}
import ascent.domtypes.EventKey
import ascent.dom
import zio.*

import scala.reflect.TypeTest
import scala.scalajs.js

/** Typed lifecycle hooks: the cast-free face of [[ascent.ast.Attr.OnMount]] / `OnUnmount` / `OnMountScoped`.
  *
  * The raw AST hooks hand back `Any` so `core` stays platform-neutral. These pin the element type first and check the
  * live node against it (a `TypeTest` the compiler writes, an `instanceof` in the browser), so the handler gets a real
  * [[ascent.dom.HTMLCanvasElement]] or whatever it names: `Lifecycle.onMount[dom.HTMLCanvasElement] { canvas => ... }`.
  * Left unpinned, the element type is [[ascent.dom.Element]]. A hook on an element of another type does not run, and
  * says so in the log.
  */
object Lifecycle:

  /** Fire once after the element is inserted into the DOM, with the live element. For `getBoundingClientRect`, canvas
    * `getContext`, focus, third-party bootstrap: anything that needs the node in the tree.
    */
  def onMount[E <: dom.Element]: OnMountPartiallyApplied[E] = OnMountPartiallyApplied()

  /** Fire once just before the element is removed. Pairs with [[onMount]] for manual acquire/release, though
    * [[onMountScoped]] is usually the cleaner choice.
    */
  def onUnmount[E <: dom.Element]: OnUnmountPartiallyApplied[E] = OnUnmountPartiallyApplied()

  /** Fire once after insertion, inside a [[zio.Scope]] tied to the element's lifetime. Acquire resources with
    * `ZIO.acquireRelease` / [[Dom.listen]] and the engine releases them on unmount, with no paired `onUnmount`. This is
    * the idiomatic way to attach a global ([[Dom.document]] / [[Dom.window]]) listener from a component.
    */
  def onMountScoped[E <: dom.Element]: OnMountScopedPartiallyApplied[E] = OnMountScopedPartiallyApplied()

  final class OnMountPartiallyApplied[E <: dom.Element] private[Lifecycle] ():
    def apply[R](handler: E => URIO[R, Unit])(using TypeTest[Any, E]): Attr[R] =
      Attr.OnMount(node => narrowed("onMount", node)(handler))

  final class OnUnmountPartiallyApplied[E <: dom.Element] private[Lifecycle] ():
    def apply[R](handler: E => URIO[R, Unit])(using TypeTest[Any, E]): Attr[R] =
      Attr.OnUnmount(node => narrowed("onUnmount", node)(handler))

  final class OnMountScopedPartiallyApplied[E <: dom.Element] private[Lifecycle] ():
    def apply[R](handler: E => URIO[R & Scope, Unit])(using TypeTest[Any, E]): Attr[R] =
      Attr.OnMountScoped(node => narrowed("onMountScoped", node)(handler))

  /** Runs `handler` on `node` when it is an `E`; any other node logs why the hook did nothing. */
  private def narrowed[E, R](hook: String, node: Any)(handler: E => URIO[R, Unit])(using
      TypeTest[Any, E]
  ): URIO[R, Unit] =
    node match
      case element: E => handler(element)
      case other      => ZIO.logWarning(s"Lifecycle.$hook did not run: ${describe(other)} is not the element it takes")

  private def describe(node: Any): String =
    node match
      case element: dom.Element => s"<${element.localName}>"
      case _                    => "a node that is not an element"
end Lifecycle

/** Imperative DOM helpers a view occasionally needs — binding listeners to non-element targets ([[document]] /
  * [[window]]), and the globals themselves — without importing `ascent.dom` or `scala.scalajs.js` directly.
  *
  * The headline is [[listen]]: a [[zio.Scope]]-native event binding that adds the listener on acquire and removes it on
  * release, so paired with [[Lifecycle.onMountScoped]] (or the [[onDocument]] / [[onWindow]] convenience attrs) it
  * cannot leak — the engine's [[Subscriptions]] runs the removal exactly when the owning subtree unmounts.
  */
object Dom:

  /** The document — for `addEventListener`-style globals and queries, re-exported so views needn't import `ascent.dom`.
    */
  inline def document: dom.Document = dom.document

  /** The window — same role as [[document]] for window-level events (`resize`, `keydown`, …). */
  inline def window: dom.Window = dom.window

  /** Focus the first element matching `selector`, if any — a synchronous DOM side effect (returns `Unit`, like
    * `preventDefaultNow`, so it reads naturally in a handler body). Lets an app-level keyboard shortcut focus a control
    * it doesn't itself build (e.g. `Dom.focusFirst(s".${Header.Input.className}")`) without threading an element
    * reference through the tree.
    */
  def focusFirst(selector: String): Unit =
    document.querySelector(selector) match
      case Some(el: dom.HTMLElement) => el.focus()
      case _                         => ()

  /** Signal (via [[Diagnostics]]) if `target` lives inside a server-owned [[ascent.ast.UI.ServerRegion]] — client code
    * shouldn't mutate server-owned DOM, since the server may patch it out from under you. Returns `true` if a violation
    * was reported. The framework's own mutation helpers call this; app code performing raw DOM writes near a region can
    * call it too.
    */
  def guardServerRegion(target: dom.EventTarget): Boolean =
    import JsDomOps.given // DomOps[dom.Node] for the generic regionContaining ancestor-walk
    target match
      case n: dom.Node =>
        ServerRegionRegistry.regionContaining(n) match
          case Some(id) =>
            Diagnostics.report(
              Diagnostics.Violation.ClientMutatedServerRegion(id),
              s"client code mutated DOM inside server-owned region `$id` — that content is the server's to manage",
            )
            true
          case None => false
      case _ => false
    end match
  end guardServerRegion

  /** Add a class token to an event target — for transient, purely-presentational state toggled straight on a node (a
    * drag-over highlight) that's deliberately kept out of the model. Takes the [[ascent.dom.EventTarget]] a typed event
    * hands back (`e.currentTarget`), so the view needn't name dom types or reach for `classList`. No-op if the target
    * isn't an element. Signals a [[Diagnostics]] violation if the target is inside a server-owned region.
    */
  def addClass(target: dom.EventTarget, cls: String): Unit =
    guardServerRegion(target)
    val d = target.asInstanceOf[js.Dynamic]
    if !js.isUndefined(d.classList) && d.classList != null then d.classList.add(cls)
    ()

  /** Remove a class token added by [[addClass]]. No-op if the target isn't an element. */
  def removeClass(target: dom.EventTarget, cls: String): Unit =
    guardServerRegion(target)
    val d = target.asInstanceOf[js.Dynamic]
    if !js.isUndefined(d.classList) && d.classList != null then d.classList.remove(cls)
    ()

  /** Bind `handler` to `event` on any [[ascent.dom.EventTarget]] (an element, [[document]], or [[window]]) for the
    * lifetime of the ambient [[zio.Scope]]: the listener is added on acquire and removed when the scope closes. Use
    * inside [[Lifecycle.onMountScoped]] so the binding's lifetime is the element's.
    *
    * The handler receives the platform-neutral [[AscentEvent]] (`key`, `targetValue`, `targetTag`, `preventDefaultNow`)
    * — the same surface element handlers get. It is total (`URIO[R, Unit]`); discharge failures with ZIO's operators.
    * Because the mount runs a handler's synchronous prefix inline, `preventDefaultNow()` in the body fires during
    * dispatch.
    */
  def listen[R](target: dom.EventTarget, event: EventKey)(
      handler: AscentEvent => URIO[R, Unit]
  ): URIO[R & Scope, Unit] =
    for
      runtime <- ZIO.runtime[R]
      listener: js.Function1[js.Any, Unit] = (raw: js.Any) =>
        val ev = DomEvent(raw.asInstanceOf[js.Dynamic])
        Unsafe.unsafe { implicit u =>
          runtime.unsafe.runOrFork(handler(ev)) match
            case Right(exit) => exit.getOrThrowFiberFailure() // sync defect: surface it
            case Left(_)     => ()                            // suspended: the runtime logs a forked defect
        }
        ()
      _ <- ZIO.acquireRelease(
        ZIO.succeed(target.addEventListener(event.domName, listener.asInstanceOf[js.Function1[dom.Event, Unit]]))
      )(_ =>
        ZIO.succeed(target.removeEventListener(event.domName, listener.asInstanceOf[js.Function1[dom.Event, Unit]]))
      )
    yield ()

  /** Attach a [[document]]-level listener for the element's lifetime: a convenience over
    * `Lifecycle.onMountScoped(el => Dom.listen(Dom.document, event)(handler(el, _)))`. The handler gets the typed
    * element it's declared on plus the event — e.g. a global `/` shortcut that focuses this input. Removed
    * automatically on unmount.
    */
  def onDocument[E <: dom.Element]: GlobalListenerPartiallyApplied[E] = GlobalListenerPartiallyApplied(() => document)

  /** Attach a [[window]]-level listener for the element's lifetime. Same contract as [[onDocument]]. */
  def onWindow[E <: dom.Element]: GlobalListenerPartiallyApplied[E] = GlobalListenerPartiallyApplied(() => window)

  final class GlobalListenerPartiallyApplied[E <: dom.Element] private[Dom] (target: () => dom.EventTarget):
    def apply[R](event: EventKey)(handler: (E, AscentEvent) => URIO[R, Unit])(using TypeTest[Any, E]): Attr[R] =
      Lifecycle.onMountScoped[E](el => listen(target(), event)(ev => handler(el, ev)))

end Dom

/** Toggle a [[ascent.css.CssClass]] on an event target imperatively — the typed sibling of [[Dom.addClass]] /
  * [[Dom.removeClass]] (which take a raw token). For transient presentational state driven from an event handler (a
  * drag-over highlight) that's kept out of the model:
  * {{{
  *   Ev.sync.onDragStart(e => e.currentTarget.foreach(_.addCssClass(Dragging)))
  *   Ev.sync.onDragEnd(e => e.currentTarget.foreach(_.removeCssClass(Dragging)))
  * }}}
  *
  * Because the class NAME is applied dynamically here — not through the `E.div(MyClass, …)` conversion that carries the
  * style into the render — [[addCssClass]] ALSO injects the class's CSS into `<head>` (idempotent by key via
  * [[DomStyleSink]], so re-adding is free). That's the whole ergonomic win over `Dom.addClass(el, MyClass.className)`:
  * one call both ensures the stylesheet and toggles the token, with no stray `MyClass.contribute` on the element and no
  * className string at the call site.
  */
extension (target: dom.EventTarget)
  /** Ensure `cls`'s CSS is where it styles `target` (its shadow root, or `<head>`), then add its class token. */
  def addCssClass(cls: ascent.css.CssClass): Unit =
    val styles = target match
      case node: dom.Node => StyleTarget.of(node)
      case _              => StyleTarget.Head
    cls.contributionBlocks.foreach((k, v) => DomStyleSink.appendSync(styles, k, v))
    Dom.addClass(target, cls.className)

  /** Remove `cls`'s class token. The CSS stays in `<head>` (harmless, and likely re-added next time). */
  def removeCssClass(cls: ascent.css.CssClass): Unit =
    Dom.removeClass(target, cls.className)
end extension
