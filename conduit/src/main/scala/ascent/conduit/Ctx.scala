package ascent.conduit

import _root_.conduit.{Conduit, Dispatchable, Optics}
import _root_.conduit.CollectionLens.*
import ascent.squawk.{Eq, Squawk}
import zio.*

/** A conduit-free handle a view uses to read the model and dispatch actions.
  *
  * Hides `Conduit`, `Optics`, `Lens`, and `CollectionLens` from view code: a view takes a `Ctx[M]` and imports only
  * `ascent.conduit.*`. It still has the full power of conduit's optics — nested-field reach and collection-element
  * focus — but expressed as plain field paths:
  *   - `ctx.squawk(_.a.b.c)` — reactive [[Squawk]] of any (possibly nested) slice,
  *   - `ctx.squawkKey(_.map, k)` — reactive `Squawk[Option[V]]` of one map entry,
  *   - `ctx.squawkAt(_.list, i)` — reactive `Squawk[Option[V]]` of one list element,
  *   - `ctx.squawkAtVector(_.vec,i)` — reactive `Squawk[Option[V]]` of one vector element,
  *   - `ctx.model` — reactive `Squawk[M]` of the whole model,
  *   - `ctx.read(_.path)` / `ctx.current` — one-shot reads, no subscription,
  *   - `ctx(action)` — dispatch.
  *
  * Fits ascent's fine-grained reactive model: the view builds a static AST once with Squawk boundaries that patch in
  * place — there is no subtree re-render.
  *
  * The handle carries the model's `Optics[M]` (always available: a conduit model is `<: Product derives Optics`), which
  * is what powers the collection-element lenses. Element subscriptions are genuinely element-scoped — conduit's
  * per-cursor listener dedup means a change to a sibling key/index does not fire this squawk.
  */
final class Ctx[M <: Product] private[conduit] (c: Conduit[M, Nothing], optics: Optics[M]):

  /** A read-only Squawk mirroring `path` of the model — e.g. `ctx.squawk(_.meta.title)`.
    *
    * Inline so conduit's public inline `zoomTo` / `subscribe(path)` expand the `lensFor` macro at the call site (the
    * same macro the example used through `Optics`/`Lens.apply`). The view needs no `Optics[M]` and never constructs a
    * lens.
    */
  inline def squawk[S](inline path: M => S)(using Eq[S]): UIO[Squawk[S]] =
    for
      seed <- c.zoomTo(path)
      src  <- ascent.squawk.sq(seed)
      _    <- c.subscribe(path)(s => src.set(s))
    yield src

  /** A reactive `Squawk[Option[V]]` of a single map entry — `ctx.squawkKey(_.todos, id)`. `None` while absent,
    * `Some(v)` once present; element-scoped (sibling keys don't fire).
    *
    * Element subscriptions register a conduit listener, so they require a [[zio.Scope]] (the one [[ascent.dsl.scoped]]
    * opens) and add an `unsubscribe` finalizer — leak-free per-row use inside a [[ascent.ast.UI.ForEach]].
    */
  inline def squawkKey[K, V](inline path: M => Map[K, V], key: K)(using
      Eq[Option[V]]
  ): URIO[Scope, Squawk[Option[V]]] =
    scopedBridge(c, optics(path).key(key))

  /** A reactive `Squawk[Option[V]]` of a single list element by index. Scope-managed — see [[squawkKey]].
    */
  inline def squawkAt[V](inline path: M => List[V], index: Int)(using
      Eq[Option[V]]
  ): URIO[Scope, Squawk[Option[V]]] =
    scopedBridge(c, optics(path).at(index))

  /** A reactive `Squawk[Option[V]]` of a single vector element by index. Scope-managed — see [[squawkKey]].
    */
  inline def squawkAtVector[V](inline path: M => Vector[V], index: Int)(using
      Eq[Option[V]]
  ): URIO[Scope, Squawk[Option[V]]] =
    scopedBridge(c, optics(path).atVector(index))

  /** A Squawk of the whole model — for views that derive several slices via `.map`. */
  def model(using Eq[M]): UIO[Squawk[M]] = bridge(c, optics)

  /** One-shot read of a slice — current value, no subscription. */
  inline def read[S](inline path: M => S): UIO[S] = c.zoomTo(path)

  /** One-shot read of the whole current model. */
  def current: UIO[M] = c.currentModel

  /** Dispatch one or more actions. App actions (`AppAction[Any, Nothing]`, contravariant in M) are accepted here
    * exactly as they are by `c(...)`.
    */
  def apply(actions: Dispatchable[M, Nothing]*): UIO[Unit] = c(actions*)
end Ctx

extension [M <: Product](c: Conduit[M, Nothing])(using optics: Optics[M])
  /** Wrap a conduit in the view-facing [[Ctx]] handle. `Optics[M]` is summoned from the model's `derives Optics` given
    * — no extra ceremony at the call site.
    */
  def ctx: Ctx[M] = new Ctx(c, optics)
