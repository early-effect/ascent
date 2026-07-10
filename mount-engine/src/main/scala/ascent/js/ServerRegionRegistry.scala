package ascent.js

/** The process-global join-point between ascent's client engine and a server that drives [[ascent.ast.UI.ServerRegion]]
  * boundaries.
  *
  * A `ServerRegion` is a container ascent mounts but does NOT reconcile internally — its interior is owned by the
  * server. To let BOTH sides get a clear signal when they attempt something nonsensical, the engine records every
  * mounted region here (keyed by its id, which is also the DOM `id` the server targets):
  *
  *   - **Server side** (the datastar client runtime applying `patch-elements`): before patching, ask [[lookup]] /
  *     [[status]]. A patch for an id that was never a region, or for one that has since unmounted, is reported via
  *     [[Diagnostics]] instead of silently mutating arbitrary DOM.
  *   - **Client side** (ascent DOM helpers / app code): [[regionContaining]] answers "is this node inside a
  *     server-owned region?" so a client mutation that would stomp server-owned DOM can be refused loudly.
  *
  * The registry is a process-global mutable `object`, so it can't be parameterized on a backend node type. It stores
  * each region's container as an opaque `AnyRef` HANDLE — all it ever needs is reference identity (never node
  * structure). [[regionContaining]] is generic over the backend node type `N` with a `given DomOps[N]`: it walks the
  * ancestor chain via `parentOf` and matches by `sameNode`, so the same registry works over the in-memory kernel and
  * the raw JS facade. Identity is real on both (kernel instances / raw browser nodes — no wrapper).
  */
object ServerRegionRegistry:

  /** The status of a region id, as seen by the registry. */
  enum Status derives CanEqual:
    /** Currently mounted and server-owned; its container handle is in [[live]]. */
    case Live

    /** Was mounted earlier in this session but has since unmounted (e.g. a `When`/`ForEach` dropped it). */
    case Vanished

    /** Never registered as a server region in this session. */
    case Unknown

  // id -> live container handle (opaque; only its identity matters). `Any` because the backend node type
  // isn't bounded to AnyRef; matched only via DomOps.sameNode in regionContaining. Removed on unmount.
  private val live: scala.collection.mutable.LinkedHashMap[String, Any] =
    scala.collection.mutable.LinkedHashMap.empty

  // Every id ever registered this session, so we can tell "vanished" from "never existed".
  private val everSeen: scala.collection.mutable.HashSet[String] =
    scala.collection.mutable.HashSet.empty

  /** Register a freshly-mounted region's container. If `id` is already live, that's a duplicate-id bug (two regions
    * claiming the same address) — reported, last registration wins. The container is stored as an opaque handle.
    */
  private[js] def register(id: String, element: Any): Unit =
    if live.contains(id) then
      Diagnostics.report(
        Diagnostics.Violation.DuplicateRegion(id),
        s"two server regions share id `$id` — the server can't address them unambiguously; last one wins",
      )
    live.update(id, element)
    everSeen.add(id)

  /** Deregister a region on unmount. */
  private[js] def unregister(id: String): Unit =
    live.remove(id)
    ()

  /** The live container handle for `id`, or `None` if not currently mounted. The caller knows the concrete node type
    * for the backend in play and can cast if it needs to.
    */
  def lookup(id: String): Option[Any] = live.get(id)

  /** Classify an id: live / vanished / unknown. */
  def status(id: String): Status =
    if live.contains(id) then Status.Live
    else if everSeen.contains(id) then Status.Vanished
    else Status.Unknown

  /** The id of the server region that contains `node` (walking up the DOM), or `None` if `node` is not inside any live
    * server region. Lets client-side mutation detect that it's about to touch server-owned DOM. Generic over the
    * backend node type — walks ancestors via [[DomOps.parentOf]] and matches stored handles by [[DomOps.sameNode]].
    */
  def regionContaining[N](node: N)(using ops: DomOps[N]): Option[String] =
    if live.isEmpty then None
    else
      var cur: Option[N]        = Some(node)
      var found: Option[String] = None
      while found.isEmpty && cur.isDefined do
        val here = cur.get
        val hit  = live.collectFirst { case (id, el) if ops.sameNode(el.asInstanceOf[N], here) => id }
        if hit.isDefined then found = hit
        else cur = ops.parentOf(here)
      found

  /** Test/utility: reset all state. */
  private[ascent] def clearForTest(): Unit =
    live.clear()
    everSeen.clear()
end ServerRegionRegistry
