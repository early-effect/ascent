package ascent.js

import ascent.ast.{AscentEvent, AstId, Attr, IdAssigner, IdMode, UI}
import ascent.css.StyleRegistry
import ascent.domtypes.AttrValue
import ascent.squawk.Subscription
import zio.*

/** Walks a [[UI]] AST and builds real DOM nodes under a parent.
  *
  * **Design:**
  *   - Every emitted [[UI.Element]] gets a `data-ascent="<id>"` attribute, where `<id>` is derived from the AST's
  *     structure via [[IdAssigner]]. The id is a stable join key between AST identity, DOM, and CSS.
  *   - [[UI.Empty]] emits NO DOM at all. A reactive boundary whose current value is `Empty` simply has zero owned
  *     nodes; sibling-walk handles positioning.
  *   - Dynamic boundaries ([[UI.ReactiveChild]], [[UI.When]], [[UI.ForEach]]) own their current DOM-node set in-memory
  *     ([[Slot]]) rather than via anchor comments. To compute "where does my new content go?" we walk later siblings
  *     looking for the first one that owns DOM, and insert before it (or append if none).
  *
  * Returns a [[Subscriptions]] bag holding every event listener / observer registered during the walk. The caller is
  * responsible for `cancelAll`-ing it at unmount time to detach them all.
  *
  * **Cross-platform:** the engine is generic over an abstract node type `N` with a `given DomOps[N]` — the ~20-method
  * DOM capability it actually needs (see [[DomOps]]). It never names a concrete `Node`/`Element`/`Document`, so the
  * SAME walker runs against the in-memory dom-core kernel (jvm/js/native) and the raw browser facade (js), each of
  * which supplies one small `DomOps[N]` instance. Style injection is a caller-supplied [[ascent.css.StyleSink]].
  */
object Mount:

  /** Mount `ui` as the new content of `parent`, returning the cleanup handle.
    *
    * `idMode` defaults to [[IdMode.HashWithRegistry]] so production users get collision-free ids without thinking about
    * it. Opt down to [[IdMode.Hash]] only if you want zero registry overhead and accept the (~10^-12 for 10k elements)
    * collision risk.
    *
    * `ops` (implicit) is the backend DOM capability — creation, mutation, attributes, events, focus. `styleSink` is
    * where authored CSS blocks go (a `<style>` injector on JS, a no-op / buffer elsewhere).
    */
  def mount[R, N](
      ui: UI[R],
      parent: N,
      idMode: IdMode = IdMode.HashWithRegistry,
  )(using ops: DomOps[N]): ZIO[R & StyleRegistry, Nothing, Subscriptions] =
    for
      // Capture the ambient runtime — and with it the environment `R` already provided to this
      // effect — ONCE. `R` flows in by ordinary ZIO composition (the caller provides it wherever
      // they run the app); the engine only needs the captured runtime where effects escape the
      // mount fiber: the browser-invoked event listener and teardown that runs at unmount.
      given Runtime[R] <- ZIO.runtime[R]
      // The per-render style catalog, provided as a scoped layer by the caller. Read it ONCE and thread it
      // as a plain value (like `cleanup`/`assigner`): the walk collapses to UIO — reactive rebuilds and
      // event handlers run off the mount fiber via the captured runtime — so it can't stay an `R` requirement.
      // Each Attr.Style records into it during the walk (see applyAttr), flushing new blocks to its sink
      // immediately, so nodes mount already styled and a style first touched inside a mid-render when/forEach
      // body still flushes.
      styles   <- ZIO.service[StyleRegistry]
      cleanup  <- Subscriptions.make
      assigner <- IdAssigner.make(idMode)
      // Top-level slot: insertion point is "append" because there's nothing after it in `parent`.
      rootSlot <- Slot.make[N]
      _        <- renderInto(ui, parent, rootSlot, () => ZIO.succeed(None), cleanup, assigner, styles)
    yield cleanup

  /** Mount `ui` as the document `<body>`, replacing the placeholder body the HTML shipped with.
    *
    * `ui`'s root should be an `E.body(...)`: the rendered node becomes the live body under `<html>`, with no wrapper
    * between page and app. The component thus carries its own page chrome (a [[ascent.css.GlobalStyle]] on the root).
    * The HTML needs only an empty `<body>` placeholder. Tearing down the returned [[Subscriptions]] removes the app's
    * body without restoring the placeholder.
    */
  def mountBody[R, N](
      ui: UI[R],
      idMode: IdMode = IdMode.HashWithRegistry,
  )(using ops: DomOps[N]): ZIO[R & StyleRegistry, Nothing, Subscriptions] =
    for
      html    <- ZIO.succeed(ops.documentElement)
      _       <- ZIO.succeed(ops.body.foreach(placeholder => ops.removeChild(html, placeholder)))
      cleanup <- mount(ui, html, idMode)
    yield cleanup

  /** Render a UI value into the given Slot, attaching whatever DOM it produces under `parent` at the position computed
    * by `insertionPoint`.
    *
    * The slot is updated to own the new DOM nodes. For Empty, the slot stays at zero nodes (the caller / sibling-walk
    * infers position from later siblings).
    */
  private def renderInto[R, N](
      ui: UI[R],
      parent: N,
      slot: Slot[N],
      insertionPoint: () => UIO[Option[N]],
      cleanup: Subscriptions,
      assigner: IdAssigner,
      styles: StyleRegistry,
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[Unit] =
    ui match
      case UI.Empty =>
        ZIO.unit

      case UI.Text(s) =>
        for
          node  <- ZIO.succeed(ops.createText(s))
          where <- insertionPoint()
          _     <- insertOne(parent, node, where)
          _     <- slot.setNodes(Vector(node))
        yield ()

      case UI.Fragment(children) =>
        // A Fragment owns N nodes that all live as siblings under `parent`. Build each
        // child into its own slot. The Fragment's own slot tracks the union of children's
        // nodes. Build LAST-CHILD-FIRST so each child's insertion point can resolve via
        // its later siblings' already-rendered first nodes.
        buildChildren(children, parent, insertionPoint, cleanup, assigner, styles).flatMap { childSlots =>
          ZIO.foreach(childSlots)(_.nodes).flatMap(blocks => slot.setNodes(blocks.flatten.toVector))
        }

      case UI.Element(tag, attrs, children) =>
        for
          el <- ZIO.succeed(ops.createElement(tag))
          // `data-ascent` makes the DOM queryable by AST id.
          id <- assigner.assign(ui)
          _  <- ZIO.succeed(ops.setAttribute(el, "data-ascent", AstId.renderAttr(id)))
          // Register OnUnmount hooks FIRST as a single source-order subscription. Doing
          // this up front (before children) means the unmount fires in declaration order:
          //   - this element's OnUnmount hooks first
          //   - then children unmount (since their cleanups were added later, they run
          //     earlier under the bag's LIFO order; element's own resources tear down last)
          // The result: a parent's "release" hook sees its children still in the DOM.
          unmountHooks = attrs.collect { case Attr.OnUnmount(h) => h }
          _ <-
            if unmountHooks.isEmpty then ZIO.unit
            else
              Subscription
                .make(
                  // Provide the ambient env so teardown is a plain UIO. `.exit` keeps a
                  // failing unmount hook from aborting the rest of teardown (cleanup must
                  // always complete or we leak observers).
                  ZIO
                    .foreachDiscard(unmountHooks)(h => h(el).exit.unit)
                    .provideEnvironment(runtime.environment)
                )
                .flatMap(cleanup.add)
          // Apply each non-lifecycle user-supplied attribute / event handler / reactive
          // binding. OnMount hooks fire AFTER the element is inserted into the parent so
          // browser APIs that need the live tree (canvas getContext, getBoundingClientRect,
          // etc.) work correctly.
          _ <- ZIO.foreachDiscard(attrs)(applyAttr(el, _, cleanup, styles))
          // `el` is fresh, so children's insertion-point fallback is "append".
          _     <- buildChildren(children, el, () => ZIO.succeed(None), cleanup, assigner, styles)
          where <- insertionPoint()
          _     <- insertOne(parent, el, where)
          _     <- slot.setNodes(Vector(el))
          // OnMount fires only now the element is in the tree; `.exit` isolates a failing hook from its siblings.
          _ <- ZIO.foreachDiscard(attrs.collect { case Attr.OnMount(h) => h })(fireOnMount(el, _))
          // OnMountScoped likewise fires post-insertion, each in its own lifetime-scoped Scope registered into cleanup.
          _ <- ZIO.foreachDiscard(attrs.collect { case Attr.OnMountScoped(h) => h })(fireOnMountScoped(el, _, cleanup))
        yield ()

      case UI.ReactiveText(src) =>
        for
          initial  <- src.get
          textNode <- ZIO.succeed(ops.createText(initial))
          where    <- insertionPoint()
          _        <- insertOne(parent, textNode, where)
          _        <- slot.setNodes(Vector(textNode))
          // Surgical in-place update: mutate the SAME text node's data on each emit.
          sub <- src.observe(s => ZIO.succeed(ops.setTextData(textNode, s)))
          _   <- cleanup.add(sub)
        yield ()

      case UI.ReactiveChild(src) =>
        mountDynamic(parent, slot, insertionPoint, cleanup, assigner, styles, src)(identity)

      case UI.When(cond, body) =>
        // A reactive boolean conditional. When true, render the body thunk; when false,
        // render Empty (= no DOM). Same machinery as ReactiveChild.
        mountDynamic(parent, slot, insertionPoint, cleanup, assigner, styles, cond)(b => if b then body() else UI.Empty)

      case fe: UI.ForEach[a, R] @unchecked =>
        // Erased type args are unprovable at runtime, but the scrutinee is `UI[R]` and
        // `ForEach[a, R'] <: UI[R]` forces `R <: R'`, so providing the mount's `R` is sound.
        mountForEach(parent, slot, insertionPoint, cleanup, assigner, styles, fe)

      case fe: UI.ForEachSignal[a, R] @unchecked =>
        mountForEachSignal(parent, slot, insertionPoint, cleanup, assigner, styles, fe)

      case UI.Scoped(build) =>
        // Open a fresh ZIO Scope, run the builder in it, then register the scope's close into
        // THIS subtree's ambient Subscriptions. Any finalizer the builder adds (a conduit
        // unsubscribe, an rAF cancel) therefore runs exactly when this node's cleanup runs —
        // on unmount, dynamic-boundary swap, or a ForEach key drop (each row owns its own
        // Subscriptions). The built UI renders into the same slot, so a Scoped node is positionally
        // transparent. The builder gets the ambient environment plus the fresh scope.
        for
          scope <- Scope.make
          ui    <- build.provideEnvironment(runtime.environment.add[Scope](scope))
          sub   <- Subscription.make(scope.close(Exit.unit))
          _     <- cleanup.add(sub)
          _     <- renderInto(ui, parent, slot, insertionPoint, cleanup, assigner, styles)
        yield ()

      case UI.ServerRegion(id, tag) =>
        // Mount an EMPTY container the server owns. We stamp its `id` (the address the server targets)
        // and a `data-ascent` structural id, register it so both sides can detect nonsensical actions,
        // and register an unmount hook that deregisters it. We deliberately build NO children and wire
        // NO observers — ascent never reconciles inside a server region.
        for
          el    <- ZIO.succeed(ops.createElement(tag))
          astId <- assigner.assign(ui)
          _     <- ZIO.succeed(ops.setAttribute(el, "data-ascent", AstId.renderAttr(astId)))
          _     <- ZIO.succeed(ops.setAttribute(el, "id", id))
          // Tag it so client-side tooling can recognise server-owned containers in the DOM.
          _     <- ZIO.succeed(ops.setAttribute(el, "data-ascent-server-region", id))
          where <- insertionPoint()
          _     <- insertOne(parent, el, where)
          _     <- slot.setNodes(Vector(el))
          _     <- ZIO.succeed(ServerRegionRegistry.register(id, el))
          sub   <- Subscription.make(ZIO.succeed(ServerRegionRegistry.unregister(id)))
          _     <- cleanup.add(sub)
        yield ()

  /** Build a sequence of children inside `parent`. Each child gets its own [[Slot]] so its insertion point can be
    * computed by walking later siblings.
    *
    * The `enclosingInsertionPoint` thunk is what to fall back to when there are no later sibling slots that own DOM —
    * typically `() => None` (append) for elements building fresh.
    *
    * Build order: LAST CHILD FIRST. Each child's insertion-point lookup walks slots[i+1..] and reads their `firstNode`.
    * If we built first-to-last, the `firstNode` of slot i+1 would be empty at the time slot i needs it. Right-to-left
    * build means by the time we render slot i, slots i+1.. already own their nodes (or have legitimately stayed empty
    * for an Empty / when(false) etc.).
    */
  private def buildChildren[R, N](
      children: Vector[UI[R]],
      parent: N,
      enclosingInsertionPoint: () => UIO[Option[N]],
      cleanup: Subscriptions,
      assigner: IdAssigner,
      styles: StyleRegistry,
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[Vector[Slot[N]]] =
    ZIO.foreach(children.indices.toVector)(_ => Slot.make[N]).flatMap { slots =>
      def insertionAfter(i: Int): UIO[Option[N]] =
        def loop(j: Int): UIO[Option[N]] =
          if j >= slots.length then enclosingInsertionPoint()
          else
            slots(j).firstNode.flatMap {
              case Some(n) => ZIO.succeed(Some(n))
              case None    => loop(j + 1)
            }
        loop(i + 1)

      // Right-to-left so each child's insertion-point can read its later siblings' slots.
      def buildAt(i: Int): UIO[Unit] =
        if i < 0 then ZIO.unit
        else
          renderInto(children(i), parent, slots(i), () => insertionAfter(i), cleanup, assigner, styles) *> buildAt(
            i - 1
          )

      buildAt(children.length - 1).as(slots)
    }

  /** The dynamic-position boundary primitive shared by ReactiveChild and When.
    *
    * When the source emits a new value `a`, we:
    *   1. cancel the previous content's child cleanup (recursively tears down nested boundaries)
    *   2. remove its DOM nodes from the parent
    *   3. build new content via `f(a)` into a fresh child cleanup
    *   4. update the slot's owned-nodes set
    *
    * Insertion point comes from the parent's `insertionPoint` thunk — when our content goes 0 → N, sibling-walk in the
    * enclosing build resolves where to splice.
    */
  private def mountDynamic[A, R, N](
      parent: N,
      slot: Slot[N],
      insertionPoint: () => UIO[Option[N]],
      outerCleanup: Subscriptions,
      assigner: IdAssigner,
      styles: StyleRegistry,
      src: ascent.squawk.Squawk[A],
  )(f: A => UI[R])(using runtime: Runtime[R], ops: DomOps[N]): UIO[Unit] =
    Ref.make[Subscriptions](null).flatMap { activeRef =>
      def render(a: A): UIO[Unit] =
        for
          // 1. Tear down the previous subtree's observers (if any).
          prev <- activeRef.get
          _    <- if prev == null then ZIO.unit else prev.cancelAll
          // 2. Remove the slot's current nodes from the DOM.
          _ <- slot.removeAll()
          // 3. Build new content into a fresh child cleanup. The renderInto call will
          //    populate the slot with the new node set.
          childCleanup <- Subscriptions.make
          _            <- renderInto(f(a), parent, slot, insertionPoint, childCleanup, assigner, styles)
          _            <- activeRef.set(childCleanup)
        yield ()

      for
        initial  <- src.get
        _        <- render(initial)
        sub      <- src.observe(a => render(a))
        _        <- outerCleanup.add(sub)
        finalize <- Subscription.make(activeRef.get.flatMap(c => if c == null then ZIO.unit else c.cancelAll))
        _        <- outerCleanup.add(finalize)
      yield ()
    }

  /** Per-item bookkeeping inside a ForEach: each item has its own Slot (so its content can change reactively without
    * the list re-rendering) and its own Subscriptions (for nested observers to detach when the key disappears).
    */
  private final case class ItemEntry[N](slot: Slot[N], cleanup: Subscriptions)

  /** Keyed-list reconciliation. Each item's id-keyed slot owns its DOM-node range; on a list change, we drop departed
    * keys, build new keys, and reorder existing entries. Items reuse their DOM nodes as long as the key persists.
    *
    * Duplicate keys: first occurrence wins, deterministic.
    */
  private def mountForEach[A, R, N](
      parent: N,
      outerSlot: Slot[N],
      outerInsertionPoint: () => UIO[Option[N]],
      outerCleanup: Subscriptions,
      assigner: IdAssigner,
      styles: StyleRegistry,
      fe: UI.ForEach[A, R],
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[Unit] =
    Ref.make(scala.collection.immutable.ListMap.empty[String, ItemEntry[N]]).flatMap { entriesRef =>
      def reconcile(items: Seq[A]): UIO[Unit] =
        for
          current <- entriesRef.get
          deduped =
            val seen = scala.collection.mutable.LinkedHashMap.empty[String, A]
            items.foreach(a => seen.getOrElseUpdate(fe.key(a), a))
            seen.toList
          newKeys = deduped.map(_._1).toSet
          // 1. Drop departed keys: remove their DOM and run their cleanups.
          _ <- ZIO.foreachDiscard(current.toList) { case (k, entry) =>
            if newKeys.contains(k) then ZIO.unit
            else entry.cleanup.cancelAll *> entry.slot.removeAll()
          }
          remaining = current.filter { case (k, _) => newKeys.contains(k) }
          // 2. Walk target order. For each key:
          //    - existing key: move its current DOM range to the right position
          //    - new key: build a fresh slot+cleanup, render into it
          rebuilt <- buildReconciledList(parent, outerInsertionPoint, remaining, deduped, fe, assigner, styles)
          _       <- entriesRef.set(rebuilt)
          // 3. Refresh the outer slot's owned-nodes view (union of all per-item nodes).
          allNodes <- ZIO.foreach(rebuilt.values.toList)(_.slot.nodes).map(_.flatten.toVector)
          _        <- outerSlot.setNodes(allNodes)
        yield ()

      for
        seed     <- fe.items.get
        _        <- reconcile(seed)
        sub      <- fe.items.observe(reconcile)
        _        <- outerCleanup.add(sub)
        finalize <- Subscription.make(
          entriesRef.get.flatMap(es => ZIO.foreachDiscard(es.values.toList)(_.cleanup.cancelAll))
        )
        _ <- outerCleanup.add(finalize)
      yield ()
      end for
    }

  /** Build the reconciled list, placing each entry's DOM range in target order. Existing entries' nodes are MOVED
    * (preserving identity, focus, observers). New entries are built fresh.
    */
  private def buildReconciledList[A, R, N](
      parent: N,
      outerInsertionPoint: () => UIO[Option[N]],
      remaining: scala.collection.immutable.ListMap[String, ItemEntry[N]],
      target: List[(String, A)],
      fe: UI.ForEach[A, R],
      assigner: IdAssigner,
      styles: StyleRegistry,
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[scala.collection.immutable.ListMap[String, ItemEntry[N]]] =
    val resultBuilder = scala.collection.immutable.ListMap.newBuilder[String, ItemEntry[N]]
    val targetVec     = target.toVector

    // Pre-allocate slots for new keys (so per-item insertion-point lookups can refer to
    // later siblings before they've rendered, in the right-to-left build below).
    ZIO
      .foreach(targetVec) { case (k, item) =>
        remaining.get(k) match
          case Some(existing) => ZIO.succeed((k, item, existing, false))
          case None           =>
            for
              slot <- Slot.make[N]
              cl   <- Subscriptions.make
            yield (k, item, ItemEntry(slot, cl), true)
      }
      .flatMap { plan =>
        def insertionAfter(i: Int): UIO[Option[N]] =
          def loop(j: Int): UIO[Option[N]] =
            if j >= plan.length then outerInsertionPoint()
            else
              plan(j)._3.slot.firstNode.flatMap {
                case Some(n) => ZIO.succeed(Some(n))
                case None    => loop(j + 1)
              }
          loop(i + 1)

        // Right-to-left: each step's insertion-point can read its later siblings' slots,
        // which are already populated by earlier (right-side) iterations.
        def stepAt(i: Int): UIO[Unit] =
          if i < 0 then ZIO.unit
          else
            val (_, item, entry, isNew) = plan(i)
            val insertion               = () => insertionAfter(i)
            val placeOrBuild            =
              if isNew then renderInto(fe.render(item), parent, entry.slot, insertion, entry.cleanup, assigner, styles)
              else moveExistingTo(parent, entry, insertion)
            placeOrBuild *> stepAt(i - 1)

        stepAt(plan.length - 1).as {
          plan.foreach { case (k, _, entry, _) => resultBuilder += k -> entry }
          resultBuilder.result()
        }
      }
  end buildReconciledList

  /** Per-item bookkeeping for a [[UI.ForEachSignal]]: like [[ItemEntry]] but the entry also owns the per-item
    * `Source[A]` the row's boundaries observe. The SAME source instance must persist for a key's whole lifetime —
    * that's what makes a row's reactive bindings keep working across parent emits. The reconciler pushes the item's
    * latest value into it via `set` (Eq-deduped).
    */
  private final case class SignalEntry[A, N](slot: Slot[N], cleanup: Subscriptions, source: ascent.squawk.Source[A])

  /** Keyed-list reconciliation with a per-item signal. Mirrors [[mountForEach]] but: a new key allocates a `Source[A]`
    * seeded with the item and the row is built ONCE against it; a surviving key has its source `set` to the latest
    * value (no rebuild, no move-of-content — the row's own boundaries patch in place); a departed key tears down.
    */
  private def mountForEachSignal[A, R, N](
      parent: N,
      outerSlot: Slot[N],
      outerInsertionPoint: () => UIO[Option[N]],
      outerCleanup: Subscriptions,
      assigner: IdAssigner,
      styles: StyleRegistry,
      fe: UI.ForEachSignal[A, R],
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[Unit] =
    given ascent.squawk.Eq[A] = fe.eq
    Ref.make(scala.collection.immutable.ListMap.empty[String, SignalEntry[A, N]]).flatMap { entriesRef =>
      def reconcile(items: Seq[A]): UIO[Unit] =
        for
          current <- entriesRef.get
          deduped =
            val seen = scala.collection.mutable.LinkedHashMap.empty[String, A]
            items.foreach(a => seen.getOrElseUpdate(fe.key(a), a))
            seen.toList
          newKeys = deduped.map(_._1).toSet
          // 1. Drop departed keys.
          _ <- ZIO.foreachDiscard(current.toList) { case (k, entry) =>
            if newKeys.contains(k) then ZIO.unit
            else entry.cleanup.cancelAll *> entry.slot.removeAll()
          }
          remaining = current.filter { case (k, _) => newKeys.contains(k) }
          // 2. Push the latest value into each SURVIVING key's source (Eq dedup makes an unchanged
          //    row a no-op). New keys are built in step 3.
          _ <- ZIO.foreachDiscard(deduped) { case (k, item) =>
            remaining.get(k) match
              case Some(entry) => entry.source.set(item)
              case None        => ZIO.unit
          }
          // 3. Place/build into target order.
          rebuilt  <- buildReconciledSignalList(parent, outerInsertionPoint, remaining, deduped, fe, assigner, styles)
          _        <- entriesRef.set(rebuilt)
          allNodes <- ZIO.foreach(rebuilt.values.toList)(_.slot.nodes).map(_.flatten.toVector)
          _        <- outerSlot.setNodes(allNodes)
        yield ()

      for
        seed     <- fe.items.get
        _        <- reconcile(seed)
        sub      <- fe.items.observe(reconcile)
        _        <- outerCleanup.add(sub)
        finalize <- Subscription.make(
          entriesRef.get.flatMap(es => ZIO.foreachDiscard(es.values.toList)(_.cleanup.cancelAll))
        )
        _ <- outerCleanup.add(finalize)
      yield ()
      end for
    }
  end mountForEachSignal

  /** The signal-variant of [[buildReconciledList]]: existing entries are MOVED (their content was already refreshed via
    * `source.set`), new entries allocate a seeded `Source[A]` and render the row once against it.
    */
  private def buildReconciledSignalList[A, R, N](
      parent: N,
      outerInsertionPoint: () => UIO[Option[N]],
      remaining: scala.collection.immutable.ListMap[String, SignalEntry[A, N]],
      target: List[(String, A)],
      fe: UI.ForEachSignal[A, R],
      assigner: IdAssigner,
      styles: StyleRegistry,
  )(using
      runtime: Runtime[R],
      ops: DomOps[N],
      eq: ascent.squawk.Eq[A],
  ): UIO[scala.collection.immutable.ListMap[String, SignalEntry[A, N]]] =
    val resultBuilder = scala.collection.immutable.ListMap.newBuilder[String, SignalEntry[A, N]]
    val targetVec     = target.toVector

    ZIO
      .foreach(targetVec) { case (k, item) =>
        remaining.get(k) match
          case Some(existing) => ZIO.succeed((k, item, existing, false))
          case None           =>
            for
              slot   <- Slot.make[N]
              cl     <- Subscriptions.make
              source <- ascent.squawk.sq(item)
            yield (k, item, SignalEntry(slot, cl, source), true)
      }
      .flatMap { plan =>
        def insertionAfter(i: Int): UIO[Option[N]] =
          def loop(j: Int): UIO[Option[N]] =
            if j >= plan.length then outerInsertionPoint()
            else
              plan(j)._3.slot.firstNode.flatMap {
                case Some(n) => ZIO.succeed(Some(n))
                case None    => loop(j + 1)
              }
          loop(i + 1)

        def stepAt(i: Int): UIO[Unit] =
          if i < 0 then ZIO.unit
          else
            val (k, item, entry, isNew) = plan(i)
            val insertion               = () => insertionAfter(i)
            val placeOrBuild            =
              if isNew then
                renderInto(
                  fe.render(k, item, entry.source),
                  parent,
                  entry.slot,
                  insertion,
                  entry.cleanup,
                  assigner,
                  styles,
                )
              else moveExistingTo(parent, signalToItemEntry(entry), insertion)
            placeOrBuild *> stepAt(i - 1)

        stepAt(plan.length - 1).as {
          plan.foreach { case (k, _, entry, _) => resultBuilder += k -> entry }
          resultBuilder.result()
        }
      }
  end buildReconciledSignalList

  /** Adapt a [[SignalEntry]] to the [[ItemEntry]] shape [[moveExistingTo]] expects (it only reads `slot`). */
  private def signalToItemEntry[A, N](e: SignalEntry[A, N]): ItemEntry[N] = ItemEntry(e.slot, e.cleanup)

  /** Move a ForEach entry's existing DOM nodes to the position before `where()`. Iteration is in document order so the
    * relative order of the entry's own nodes is preserved at the destination. The `sameNode` guard skips a node that's
    * already at the target (no-op move), matching the browser's own insertBefore(n, n) semantics.
    */
  private def moveExistingTo[N](
      parent: N,
      entry: ItemEntry[N],
      where: () => UIO[Option[N]],
  )(using ops: DomOps[N]): UIO[Unit] =
    for
      target <- where()
      nodes  <- entry.slot.nodes
      _      <- ZIO.succeed {
        nodes.foreach { n =>
          target match
            case Some(t) if !ops.sameNode(t, n) => ops.insert(parent, n, Some(t))
            case None                           => ops.insert(parent, n, None)
            case _                              => ()
        }
      }
    yield ()

  private def insertOne[N](parent: N, node: N, where: Option[N])(using ops: DomOps[N]): UIO[Unit] =
    ZIO.succeed(ops.insert(parent, node, where))

  /** Write a static attribute, wire an event listener, or record a style contribution into the render's
    * [[StyleRegistry]] (see `Attr.Style`). OnMount hooks are deliberately skipped here — they fire post-insertion in a
    * second pass (see the `UI.Element` case). OnUnmount hooks register a Subscription whose `cancel` runs the user
    * effect at teardown.
    */
  private def applyAttr[R, N](el: N, attr: Attr[R], cleanup: Subscriptions, styles: StyleRegistry)(using
      runtime: Runtime[R],
      ops: DomOps[N],
  ): UIO[Unit] =
    attr match
      case Attr.StaticAttr(name, value) =>
        ZIO.succeed(setAttr(el, name, value))
      case Attr.Style(blocks) =>
        // Collect this element's CSS contribution into the render's catalog, flushing newly-seen blocks
        // to its sink now (before insertion) so the node mounts already styled. No DOM attribute results.
        styles.record(blocks)
      case Attr.EventHandler(event, handler) =>
        wireEventListener(el, event, handler, cleanup)
      case Attr.ReactiveAttr(name, src) =>
        // Reactive `class` is special: the binding engine tracks the SET of tokens this
        // squawk most recently added, so each emit removes the prior set and adds the
        // new one — without disturbing tokens contributed by sibling static class attrs
        // or by other reactive class squawks. Effectively a per-squawk classList diff.
        if name == "class" then
          val prevTokens                        = scala.collection.mutable.Set.empty[String]
          def writeReactive(v: AttrValue): Unit =
            val incoming = v match
              case AttrValue.Str(s) => s.trim
              case _                => ""
            val incomingTokens = incoming.split("\\s+").filter(_.nonEmpty).toSet
            // `remove` the tokens THIS squawk previously added but that aren't incoming; `add` the
            // newly-incoming ones. Tokens contributed by sibling static/other-reactive class attrs
            // are untouched (per-squawk diff via classAdd/classRemove).
            prevTokens.iterator.filterNot(incomingTokens).foreach(t => ops.classRemove(el, t))
            (incomingTokens -- prevTokens).foreach(t => ops.classAdd(el, t))
            prevTokens.clear()
            prevTokens ++= incomingTokens
          end writeReactive
          for
            initial <- src.get
            _       <- ZIO.succeed(writeReactive(initial))
            sub     <- src.observe(v => ZIO.succeed(writeReactive(v)))
            _       <- cleanup.add(sub)
          yield ()
        else
          for
            initial <- src.get
            _       <- ZIO.succeed(setAttr(el, name, initial))
            sub     <- src.observe(v => ZIO.succeed(setAttr(el, name, v)))
            _       <- cleanup.add(sub)
          yield ()
      case Attr.ReactiveClasses(src) =>
        // Typed reactive class SET. On each emit: (1) record every emitted class's CSS into the render (idempotent
        // by key — a class's stylesheet appears the moment it's first selected, no up-front contribution needed), then
        // (2) diff the `class` tokens exactly like the reactive-`class`-string path above — remove tokens THIS binding
        // added but that aren't in the new set, add the newly-present ones, leaving sibling attrs' tokens untouched.
        val prevTokens                                             = scala.collection.mutable.Set.empty[String]
        def write(classes: Set[Attr.ClassContribution]): UIO[Unit] =
          val incomingTokens = classes.map(_.className)
          styles.record(classes.toVector.flatMap(_.styleBlocks)) *> ZIO.succeed {
            prevTokens.iterator.filterNot(incomingTokens).foreach(t => ops.classRemove(el, t))
            (incomingTokens -- prevTokens).foreach(t => ops.classAdd(el, t))
            prevTokens.clear()
            prevTokens ++= incomingTokens
          }
        for
          initial <- src.get
          _       <- write(initial)
          sub     <- src.observe(write)
          _       <- cleanup.add(sub)
        yield ()
      case Attr.OnMount(_)       => ZIO.unit // fired after element is inserted (above)
      case Attr.OnMountScoped(_) => ZIO.unit // fired after element is inserted (above), in a lifetime-scoped Scope
      case Attr.OnUnmount(_)     => ZIO.unit // registered as a single source-order Subscription (above)

  /** Run a single OnMount handler INLINE on the mount fiber. Mount-time hooks are sync-completing in the common case
    * (canvas `getContext`, focus, getBoundingClientRect, third-party library bootstrap), and the rest of `Mount.mount`
    * is one chained UIO so inlining here keeps observed-state semantics simple.
    *
    * The handler's `exit` is captured so a failing hook becomes a successful `ZIO.unit` — one bad hook can't torpedo
    * its siblings or the rest of the mount.
    */
  private def fireOnMount[R, N](el: N, handler: Any => URIO[R, Unit])(using runtime: Runtime[R]): UIO[Unit] =
    handler(el).provideEnvironment(runtime.environment).exit.unit

  /** Run an OnMountScoped handler INLINE, after insertion, in a fresh [[zio.Scope]] tied to the element's lifetime —
    * the same machinery as [[ascent.ast.UI.Scoped]]. The handler's finalizers run when `scope.close` fires, registered
    * as a `Subscription` in this element's [[Subscriptions]] — teardown on unmount / dynamic swap / ForEach key drop,
    * LIFO with the rest of the element's resources. `.exit` isolates a failing hook from its siblings.
    */
  private def fireOnMountScoped[R, N](el: N, handler: Any => URIO[R & Scope, Unit], cleanup: Subscriptions)(using
      runtime: Runtime[R]
  ): UIO[Unit] =
    for
      scope <- Scope.make
      _     <- handler(el).provideEnvironment(runtime.environment.add[Scope](scope)).exit.unit
      sub   <- Subscription.make(scope.close(Exit.unit))
      _     <- cleanup.add(sub)
    yield ()

  /** Write an attribute / property to a DOM element. value/checked go through DOM properties (via [[DomOps]]);
    * everything else uses setAttribute. Handles the controlled-input caret guard for `value`.
    */
  private def setAttr[N](el: N, name: String, value: AttrValue)(using ops: DomOps[N]): Unit =
    (name, value) match
      case ("value", AttrValue.Str(s)) =>
        // Controlled-input caret guard: writing `.value` to a FOCUSED input moves the caret to the
        // end, so only write when the value differs OR the element isn't focused. `hasValueProperty`
        // is true for input/textarea/select; anything else falls through to setAttribute. `isActive`
        // is always false off a real browser, so in-memory always writes (the guard is browser-only).
        if ops.hasValueProperty(el) then
          val live = ops.getValueProperty(el)
          if !(ops.isActive(el) && live == s) then ops.setValueProperty(el, s)
        else ops.setAttribute(el, name, s)

      case ("checked", AttrValue.Bool(b)) => ops.setCheckedProperty(el, b)
      case ("checked", AttrValue.Str(_))  => ops.setCheckedProperty(el, true)
      case ("checked", AttrValue.Absent)  => ops.setCheckedProperty(el, false)

      // The `class` attribute is the only HTML attribute that's genuinely multi-valued
      // (space-separated tokens). Multiple `Attr.StaticAttr("class", ...)` on the same
      // element should COMPOSE — useful for layering a CssClass + a Tooltip + a state-
      // class on the same button. Empty/Absent values skip cleanly (don't wipe what an
      // earlier static class contributed). To explicitly clear, pass an empty string in a
      // ReactiveAttr — that path uses the diff-based writer which only removes tokens IT added.
      case ("class", AttrValue.Str(s)) =>
        s.split("\\s+").filter(_.nonEmpty).foreach(t => ops.classAdd(el, t))
      case ("class", _) => () // Absent/Bool: skip — preserves merge composition

      case (_, AttrValue.Absent)      => ops.removeAttribute(el, name)
      case (_, AttrValue.Bool(true))  => ops.setAttribute(el, name, "")
      case (_, AttrValue.Bool(false)) => ops.removeAttribute(el, name)
      case (_, AttrValue.Str(s))      => ops.setAttribute(el, name, s)

  /** Attach a DOM event listener that runs the AST-side handler with `runOrFork`: the effect executes synchronously on
    * the browser's dispatch stack up to its first async boundary, and only the suspending remainder is forked onto the
    * next macrotask.
    *
    * `runOrFork`, not `fork`: forking the whole effect defers every DOM call to a later tick — too late for what the
    * browser requires DURING dispatch (`preventDefault` on dragover, `dataTransfer.setData` on dragstart, focus
    * management). A handler's synchronous prefix must run inline. Not `run` either: on Scala.js it throws the moment a
    * handler suspends. `runOrFork` is the union — run inline, fork at the first real suspension. This logic is pure ZIO
    * (platform-neutral); the backend's [[DomOps.addListener]] owns native-event → [[AscentEvent]] conversion and the
    * actual registration.
    *
    * Handlers are total (`URIO[R, Unit]`): the author discharges recoverable failures with ZIO's own operators. A
    * genuine DEFECT is NOT swallowed — for a synchronously-completing handler we `getOrThrowFiberFailure`, so the
    * defect escapes dispatch loudly; for a forked one the runtime's own unhandled-failure logger reports it.
    */
  private def wireEventListener[R, N](
      el: N,
      event: String,
      handler: AscentEvent => URIO[R, Unit],
      cleanup: Subscriptions,
  )(using runtime: Runtime[R], ops: DomOps[N]): UIO[Unit] =
    for
      listenerToken <- ZIO.succeed {
        ops.addListener(
          el,
          event,
          (ev: AscentEvent) =>
            Unsafe.unsafe { implicit u =>
              runtime.unsafe.runOrFork(handler(ev)) match
                case Right(exit) => exit.getOrThrowFiberFailure() // sync defect: surface it, don't swallow
                case Left(_)     => ()                            // suspended: the runtime reports a forked defect
            },
        )
      }
      sub <- Subscription.make(ZIO.succeed(ops.removeListener(el, event, listenerToken)))
      _   <- cleanup.add(sub)
    yield ()

end Mount
