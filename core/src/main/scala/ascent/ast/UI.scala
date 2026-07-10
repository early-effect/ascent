package ascent.ast

import ascent.squawk.Squawk
import zio.{Scope, URIO}

/** The pure, platform-neutral UI AST.
  *
  * Every layer agrees on this type:
  *   - the DSL builds it (Element, Text, conversions from Squawk → ReactiveText/ReactiveChild)
  *   - the JS mount interprets it (real DOM via the dom-facade)
  *   - a future SSR backend will render it (HTML string)
  *
  * **Environment `R`.** `UI[-R]` is parameterized by the ZIO environment its effectful parts require — event handlers,
  * lifecycle hooks, and [[UI.Scoped]] builders. It is CONTRAVARIANT in `R`, exactly like `ZIO[-R, …]`: a `UI[Any]` (no
  * requirements) is usable wherever a `UI[R]` is expected, and an element built from children needing `R1` and `R2`
  * infers `UI[R1 & R2]` — the same environment-combination ZIO gives you. Purely-static subtrees are `UI[Any]`. The
  * environment is provided once, at [[ascent.js.Mount.mount]].
  *
  * Reactive boundaries (ReactiveText, ReactiveChild, When, ForEach) are first-class node variants that carry a
  * [[Squawk]]. The binding engine identifies these by pattern-matching and registers observers — no separate "reactive
  * metadata" channel needed. Reactive *text* and *attribute* observers are mount-internal DOM mutation (no user
  * effect), so those nodes are `UI[Any]`.
  */
sealed trait UI[-R]

object UI:
  /** A static element node. */
  final case class Element[R](tag: String, attrs: Vector[Attr[R]], children: Vector[UI[R]]) extends UI[R]

  /** A static text node. */
  final case class Text(value: String) extends UI[Any]

  /** Renders nothing. Used as the "false branch" of a static `when`, the empty Option case, etc. */
  case object Empty extends UI[Any]

  /** A list of children at the same level — useful when a function returns multiple siblings without a wrapping
    * element. The binding engine inserts each child between persistent anchor nodes for safe replacement.
    */
  final case class Fragment[R](children: Vector[UI[R]]) extends UI[R]

  /** Fast-path reactive boundary: only a text node mutates on change. The binding engine subscribes the [[Squawk]] and
    * writes `textNode.data` directly — no subtree rebuild. The observer is internal DOM mutation, so this needs no
    * environment.
    */
  final case class ReactiveText(src: Squawk[String]) extends UI[Any]

  /** General reactive boundary: the bound subtree is replaced wholesale on each emit. The binding engine cancels the
    * previous subtree's observers (recursively) before swapping.
    */
  final case class ReactiveChild[R](src: Squawk[UI[R]]) extends UI[R]

  /** Conditional rendering. The body is a thunk so it builds fresh each true-transition, keeping per-render local state
    * correct.
    */
  final case class When[R](cond: Squawk[Boolean], body: () => UI[R]) extends UI[R]

  /** Keyed-list boundary. The binding engine reuses existing DOM nodes for unchanged keys (preserving focus/caret) and
    * only builds new subtrees for new keys.
    */
  final case class ForEach[A, R](
      items: Squawk[Seq[A]],
      key: A => String,
      render: A => UI[R],
  ) extends UI[R]

  /** Keyed-list boundary with a per-item reactive `Squawk[A]`. Each surviving key keeps a stable, memoized signal: the
    * row is built ONCE and each parent-list emit pushes the item's latest value into that signal (Eq-deduped). The row
    * binds its own boundaries to `signal`, so only the fields that change repaint — the conduit-free path to
    * fine-grained per-item updates, without rebuilding the row.
    */
  final case class ForEachSignal[A, R](
      items: Squawk[Seq[A]],
      key: A => String,
      render: (String, A, Squawk[A]) => UI[R],
  )(using val eq: ascent.squawk.Eq[A])
      extends UI[R]

  /** An effectful subtree builder run at mount time within a ZIO [[zio.Scope]] (plus the ambient environment `R`) — the
    * boundary for a subtree that must acquire a resource scoped to its own lifetime (a per-item conduit subscription, a
    * `requestAnimationFrame` loop, a third-party widget). The engine opens a fresh `Scope`, runs `build`, and renders
    * the resulting `UI`; `build` registers teardown the idiomatic ZIO way (`ZIO.addFinalizer` / `acquireRelease`), and
    * the finalizers run when the owning boundary tears down (unmount, dynamic swap, ForEach key drop). Composes with
    * [[ForEach]]: a row returning `Scoped` gets per-row resources tied to that row's cleanup.
    */
  final case class Scoped[R](build: URIO[R & Scope, UI[R]]) extends UI[R]

  /** A server-owned region: an empty container the client mounts but deliberately does NOT manage internally. Its
    * interior is filled and updated by the server (e.g. datastar `patch-elements` addressed to [[id]]); ascent's
    * reactive engine never reconciles inside it. This makes the client-built vs server-driven divide a type-level fact:
    * the node carries no children (so a client repaint and a server patch can never fight over the same nodes), and the
    * engine registers the region by [[id]] so both sides are warned on a nonsensical action (client mutating the
    * interior, or server patching an unmounted region). `id` is the address both agree on — client renders
    * `<tag id="<id>">`, server targets `#<id>`; `tag` is the container element (default `div`).
    */
  final case class ServerRegion(id: String, tag: String = "div") extends UI[Any]
end UI
