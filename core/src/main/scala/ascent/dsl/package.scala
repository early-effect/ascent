package ascent

import ascent.ast.{AscentEvent, Attr, UI}
import ascent.domtypes.{AttrKey, ElementKey, EventKey, VoidElementKey}
import ascent.squawk.Squawk
import zio.{Scope, URIO}

/** The DSL — what users actually write. Concerns:
  *
  *   - Make element constructors callable: `divKey(idKey("x"), "hello")` returns a [[UI.Element]]. A void element
  *     ([[VoidElementKey]]) accepts attribute/event args only — children are a compile error.
  *   - Make attribute keys callable with either a value or a [[Squawk]]: `idKey("x")` / `idKey(squawk)` produce a
  *     [[Attr.StaticAttr]] / [[Attr.ReactiveAttr]] respectively.
  *   - Make event keys callable with an effectful handler: `Events.onInput(e => ...)`.
  *   - Provide top-level control-flow helpers: [[when]], [[forEach]], [[text]], [[fragment]], [[scoped]].
  *
  * The conversions in [[dsl.Arg]] accept bare strings, ints, options, and Squawks at the call site without ceremony.
  * Everything threads the ZIO environment `R` the effectful parts require.
  */
package object dsl:

  /** Make element constructors callable: `divKey(args*)` builds a [[UI.Element]].
    *
    * Args are flattened (`Seq`s splat, `None` becomes `Empty`), then partitioned by kind: `AttrArg` lands in `attrs`,
    * `ChildArg` lands in `children`, in source order. Order is preserved so the mount renders attrs and children
    * deterministically.
    */
  extension (key: ElementKey)
    def apply[R](args: Arg[R]*): UI[R] =
      val attrs    = Vector.newBuilder[Attr[R]]
      val children = Vector.newBuilder[UI[R]]
      collectArgs(args)(attrs += _, children += _)
      UI.Element(key.domName, attrs.result(), children.result())

  /** A void element accepts attribute/event args ONLY — `VoidArg*`. Passing a child does not type-check (children lift
    * to a child-bearing `Arg`, never a `VoidArg`).
    */
  extension (key: VoidElementKey)
    def apply[R](args: VoidArg[R]*): UI[R] =
      val attrs = Vector.newBuilder[Attr[R]]
      collectVoidArgs(args)(attrs += _)
      UI.Element(key.domName, attrs.result(), Vector.empty)

  /** Make attribute keys callable. `idKey("x")` lifts via the codec to a [[Attr.StaticAttr]]; `idKey(squawk)` lifts to
    * a [[Attr.ReactiveAttr]] that maps the codec on each emit.
    */
  extension [V](key: AttrKey[V])
    def apply(value: V): Attr[Any]       = Attr.from(key, value)
    def apply(src: Squawk[V]): Attr[Any] = Attr.fromSquawk(key, src)

  /** Make event keys callable: `Events.onInput(e => ...)` lifts to an [[Attr.EventHandler]] on the key's DOM event
    * name. The handler is total — `URIO[R, Unit]` — so failures are discharged with ZIO's combinators rather than
    * swallowed by the engine. It receives the platform-neutral [[AscentEvent]] carrying `targetValue` (two-way binding)
    * and `key` (Enter/Escape). For typed DOM events (e.g. `dom.PointerEvent`), use `ascent.js`'s
    * `TypedEvents.onClick(...)`.
    */
  extension (key: EventKey)
    def apply[R](handler: AscentEvent => URIO[R, Unit]): Attr[R] =
      Attr.EventHandler(key.domName, handler)

    /** Synchronous-handler sugar: `Events.onInput.sync { e => ... }` takes a plain side-effecting block and lifts it
      * via `ZIO.attempt(...).orDie`. The mount runs a handler's synchronous prefix inline on the dispatch stack, so a
      * `.sync` handler's DOM effects take place during event dispatch — what `preventDefault` / drag-and-drop require.
      * A throw becomes a defect (surfaced, not lost). Use the plain [[apply]] when the handler is genuinely effectful
      * and you want to compose / recover.
      */
    def sync(handler: AscentEvent => Unit): Attr[Any] =
      Attr.EventHandler(key.domName, e => zio.ZIO.attempt(handler(e)).orDie)
  end extension

  // --- control-flow helpers ---

  /** Static conditional: returns `body` if `cond` is true, otherwise [[UI.Empty]]. The body is evaluated only on the
    * true branch — the binding engine never sees an unused subtree.
    */
  def when[R](cond: Boolean)(body: => UI[R]): UI[R] =
    if cond then body else UI.Empty

  /** Reactive conditional: builds a [[UI.When]] that the binding engine subscribes to. The body is a thunk so it builds
    * fresh each true-transition (per-render local state stays correct).
    */
  def when[R](cond: Squawk[Boolean])(body: => UI[R]): UI[R] =
    UI.When(cond, () => body)

  /** Keyed-list rendering. The key fn provides identity stability so the binding engine can reuse existing DOM nodes
    * (preserving focus/caret) when a list reorders. The render fn builds the per-item subtree from a snapshot value.
    */
  def forEach[A, R](items: Squawk[Seq[A]])(key: A => String)(render: A => UI[R]): UI[R] =
    UI.ForEach(items, key, render)

  /** Keyed-list rendering with a per-item reactive `Squawk[A]` — the conduit-free way to get fine-grained per-row
    * updates. `render(key, initial, signal)` runs ONCE per key; the engine feeds the item's latest value into `signal`
    * on each parent emit (Eq-deduped), so a row repaints only the boundaries bound to fields that actually changed. The
    * `Eq[A]` gates that dedup; it resolves structurally for case classes via `derives Eq`.
    */
  def forEachSignal[A, R](items: Squawk[Seq[A]])(key: A => String)(
      render: (String, A, Squawk[A]) => UI[R]
  )(using ascent.squawk.Eq[A]): UI[R] =
    UI.ForEachSignal(items, key, render)

  /** Explicit text child — useful inside a conditional that returns `UI`, where Scala can't pick the `String -> Arg`
    * conversion.
    */
  def text(s: String): UI[Any] = UI.Text(s)

  /** Group several children as one [[UI.Fragment]] — siblings without a wrapping element. Args are flattened as in the
    * element constructor; non-child args (attributes) are ignored — a fragment has no element to attach them to.
    */
  def fragment[R](args: Arg[R]*): UI[R] =
    val children = Vector.newBuilder[UI[R]]
    collectArgs(args)(_ => (), children += _)
    UI.Fragment(children.result())

  /** Effectful, lifetime-scoped subtree. `build` runs at mount time inside the ambient environment `R` plus a fresh ZIO
    * [[zio.Scope]] the body uses to acquire resources whose teardown is tied to this node's lifetime. When the owning
    * boundary tears down (unmount, dynamic swap, ForEach key drop) the engine closes the scope and its finalizers run.
    */
  def scoped[R](build: URIO[R & Scope, UI[R]]): UI[R] = UI.Scoped(build)

  /** A server-owned region addressed by `id`. The client mounts an empty `<tag id="id">` container and leaves its
    * interior alone; the server fills/updates it (e.g. datastar `patch-elements` targeting `#id`). The engine records
    * the region so the client and server each get a clear signal on a nonsensical action — see [[UI.ServerRegion]].
    */
  def serverRegion(id: String, tag: String = "div"): UI[Any] = UI.ServerRegion(id, tag)

  // --- arg flattening (shared by element / fragment constructors) ---

  private def collectArgs[R](args: Seq[Arg[R]])(onAttr: Attr[R] => Unit, onChild: UI[R] => Unit): Unit =
    def collect(a: Arg[R]): Unit = a match
      case Arg.Empty             => ()
      case Arg.ChildArg(ui)      => onChild(ui)
      case Arg.AttrArg(attr)     => onAttr(attr)
      case Arg.ArgsArg(more)     => more.foreach(collect)
      case Arg.VoidArgsArg(more) => more.foreach(collect)
    args.foreach(collect)

  private def collectVoidArgs[R](args: Seq[VoidArg[R]])(onAttr: Attr[R] => Unit): Unit =
    def collect(a: VoidArg[R]): Unit = a match
      case Arg.Empty             => ()
      case Arg.AttrArg(attr)     => onAttr(attr)
      case Arg.VoidArgsArg(more) => more.foreach(collect)
    args.foreach(collect)

end dsl
