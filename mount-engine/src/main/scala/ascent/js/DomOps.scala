package ascent.js

import ascent.ast.AscentEvent

/** The NARROW DOM capability the [[Mount]] engine needs — the ~20 operations it actually performs on nodes, and nothing
  * more.
  *
  * Mount is generic over an abstract node type `N` with a `given DomOps[N]` rather than committing to a concrete
  * `Node`/`Element`/`Document` hierarchy. This keeps every backend's implementation MINIMAL: a backend supplies one
  * `DomOps[N]` instance (~20 methods) instead of satisfying dom-core's full structural traits (Document alone has 370
  * abstract members). It's the same "caller supplies the capability" pattern as [[ascent.css.Matchable]] (for the
  * selector engine) and [[EventCodec]] (for events).
  *
  * Two backends implement it:
  *   - **in-memory** (`DomOps[ascent.domcore.generated.Node]`): forwards to the tested in-memory kernel; runs on
  *     jvm/js/native, so the whole Mount suite runs browser-free.
  *   - **JS** (`DomOps[ascent.dom.Node]`): forwards to the real `@js.native` dom-facade. Because it operates on RAW
  *     browser nodes (no wrapper), node identity is free — `parentNode`/`activeElement` reads are `eq` to stored
  *     references with no cache needed. (An earlier wrapper design needed a WeakMap identity cache to preserve `eq`;
  *     DomOps eliminates the wrapper, and with it the whole identity hazard.)
  *
  * `N` is a SINGLE node type spanning elements, text, comments, and the document — the concrete backends model these as
  * one facade/kernel hierarchy, and DomOps' kind-specific operations (`setTextData`, `setValueProperty`, `classAdd`, …)
  * encapsulate the discrimination so Mount never pattern-matches on node kind.
  */
trait DomOps[N]:

  // --- creation (Document-level) ---

  /** Create an element node for `tag` (e.g. `"div"`). */
  def createElement(tag: String): N

  /** Create a text node with the given initial data. */
  def createText(data: String): N

  /** Create a comment node with the given data. */
  def createComment(data: String): N

  // --- tree mutation ---

  /** Insert `child` into `parent` immediately before `ref`; if `ref` is `None`, append at the end. */
  def insert(parent: N, child: N, ref: Option[N]): Unit

  /** Remove `child` from `parent`. A no-op if `child` isn't a child of `parent`. */
  def removeChild(parent: N, child: N): Unit

  /** The node's current parent, or `None` if detached / at the tree root. */
  def parentOf(node: N): Option[N]

  // --- attributes ---

  /** Set the string attribute `name` to `value`. */
  def setAttribute(el: N, name: String, value: String): Unit

  /** Remove the attribute `name`. */
  def removeAttribute(el: N, name: String): Unit

  /** The attribute `name`'s value, or `None` if absent. */
  def getAttribute(el: N, name: String): Option[String]

  // --- class-token list (per-element `classList`) ---

  /** Add a single class token (dedup/space-join handled by the backend's classList). */
  def classAdd(el: N, token: String): Unit

  /** Remove a single class token. */
  def classRemove(el: N, token: String): Unit

  // --- form-control properties (the value/checked caret-guard surface) ---

  /** True if `el` is an element whose live `value` property is meaningful (input/textarea/select). */
  def hasValueProperty(el: N): Boolean

  /** The element's live `value` property (empty string if it has none). */
  def getValueProperty(el: N): String

  /** Set the element's live `value` property. */
  def setValueProperty(el: N, value: String): Unit

  /** Set the element's live `checked` property (a no-op on elements that don't have one). */
  def setCheckedProperty(el: N, checked: Boolean): Unit

  // --- text-node data ---

  /** Set a text node's `data` in place (the surgical ReactiveText update). */
  def setTextData(textNode: N, data: String): Unit

  // --- focus (for the controlled-input caret guard) ---

  /** True if `node` is the document's currently-focused element. In-memory backends return `false` always (no
    * interactive focus off a real browser) — so the caret guard's "skip write" branch is browser-only, by design.
    */
  def isActive(node: N): Boolean

  // --- events ---

  /** Register `handler` for `eventType` on `el`, returning a token that [[removeListener]] uses to detach exactly this
    * registration. The backend converts its native event into [[AscentEvent]] before calling `handler` (the JS backend
    * also applies its `runOrFork` dispatch discipline — see the js instance).
    */
  def addListener(el: N, eventType: String, handler: AscentEvent => Unit): DomOps.ListenerToken

  /** Detach a listener previously registered via [[addListener]]. */
  def removeListener(el: N, eventType: String, token: DomOps.ListenerToken): Unit

  // --- document navigation (for mountBody) ---

  /** The document's root element (`<html>`). */
  def documentElement: N

  /** The document's `<body>`, or `None` if absent. */
  def body: Option[N]

  // --- identity (Mount reconciliation compares nodes by reference) ---

  /** Reference identity between two nodes — the reconciler's `moveExistingTo` and the caret guard rely on it. Both
    * backends use real reference equality (raw facade nodes on JS, kernel instances in-memory).
    */
  def sameNode(a: N, b: N): Boolean
end DomOps

object DomOps:
  def apply[N](using ops: DomOps[N]): DomOps[N] = ops

  /** An opaque handle to a registered listener, returned by [[DomOps.addListener]] and consumed by
    * [[DomOps.removeListener]]. Backends carry whatever they need to detach exactly that registration (the JS backend
    * stores the wrapped `js.Function`; the in-memory backend stores the `Event => Unit` closure).
    */
  trait ListenerToken
end DomOps
