package ascent.ast

import ascent.domtypes.{AttrKey, AttrValue}
import ascent.squawk.Squawk
import zio.{Scope, URIO}

/** The platform-neutral representation of an HTML attribute / event handler in the AST.
  *
  * Like [[UI]], `Attr[-R]` is CONTRAVARIANT in the ZIO environment its effectful parts require. A static attribute or a
  * reactive-attribute binding needs nothing (`Attr[Any]`); an event handler or lifecycle hook carries its requirement
  * `R`, which the mount provides once.
  *
  * Consumed by the binding engine:
  *   - [[Attr.StaticAttr]] — name + already-encoded value (the codec ran at lift time).
  *   - [[Attr.ReactiveAttr]] — name + a Squawk; the engine subscribes and writes on each emit.
  *   - [[Attr.EventHandler]] — DOM event name + an effectful, TOTAL handler (`URIO[R, Unit]`). The error channel is
  *     `Nothing` on purpose: a handler must discharge its own failures with ZIO's own combinators (`catchAll`, `orDie`,
  *     `retry`, `tapError`, …). There is no ascent-specific error type, and the mount no longer swallows failures — a
  *     handler that dies surfaces loudly via ZIO's unhandled-failure reporting.
  *   - [[Attr.OnMount]] — lifecycle hook firing once after the element is inserted into the DOM. Receives the live
  *     element so the handler can call browser APIs that require a real node (e.g. `getContext("2d")` on a canvas,
  *     `getBoundingClientRect`, focus management, third-party library bootstrap).
  *   - [[Attr.OnUnmount]] — lifecycle hook firing once just before the element is removed from the DOM (during cleanup
  *     teardown, dynamic-boundary swap, or ForEach key drop). Pairs with `OnMount` for resources that need acquire /
  *     release semantics tied to element lifetime — `requestAnimationFrame` IDs, observers, third-party teardown.
  *   - [[Attr.Style]] — CSS blocks a style primitive contributes; collected into the render's stylesheet, never a DOM
  *     attribute.
  *   - [[Attr.ReactiveClasses]] — a reactive SET of style classes: records each class's CSS and diffs the `class`
  *     tokens on every emit. (Plus [[Attr.OnMountScoped]], the scope-native lifecycle hook.)
  */
sealed trait Attr[-R]

object Attr:
  final case class StaticAttr(name: String, value: AttrValue)                            extends Attr[Any]
  final case class ReactiveAttr(name: String, value: Squawk[AttrValue])                  extends Attr[Any]
  final case class EventHandler[R](event: String, handler: AscentEvent => URIO[R, Unit]) extends Attr[R]

  /** A mount-time lifecycle hook. The element argument is opaque (`Any`) at the AST level so `core` stays
    * platform-neutral; the JS backend casts it to its `dom.Element` facade before invoking the hook, while SSR / JVM
    * backends can skip it entirely.
    */
  final case class OnMount[R](handler: Any => URIO[R, Unit]) extends Attr[R]

  /** An unmount-time lifecycle hook. Same opacity story as [[OnMount]]. Fires exactly once when the element's owning
    * [[ascent.js.Subscriptions]] runs — whether via a top-level cleanup call, a dynamic-boundary swap, or a ForEach key
    * drop.
    */
  final case class OnUnmount[R](handler: Any => URIO[R, Unit]) extends Attr[R]

  /** A mount-time hook that runs inside a fresh [[zio.Scope]] tied to the element's lifetime. Like [[OnMount]] it fires
    * after insertion and receives the live element (opaque `Any`; the js backend casts to its facade), but the handler
    * may `ZIO.acquireRelease` / `ZIO.addFinalizer` — the engine closes the scope when the element's
    * [[ascent.js.Subscriptions]] runs, so every finalizer the handler registered releases automatically. This is the
    * scope-native replacement for hand-pairing [[OnMount]] with [[OnUnmount]] to add then remove a resource (a global
    * event listener, a `requestAnimationFrame`, an observer).
    */
  final case class OnMountScoped[R](handler: Any => URIO[R & Scope, Unit]) extends Attr[R]

  /** CSS blocks a style primitive contributes when applied to an element — `(sink-key, rendered-css)` pairs. The mount
    * engine COLLECTS these into its per-render `StyleRegistry` (which flushes them to a `<style>` sink); they never
    * become a DOM attribute. This is how styles reach a render's stylesheet as data carried through the AST, so each
    * render's CSS is exactly the styles its own tree references — no process-global catalog. Opaque to `core` (which
    * cannot depend on the css module): just strings. Contributes no DOM, so it's void-safe.
    */
  final case class Style(blocks: Vector[(String, String)]) extends Attr[Any]

  /** One class in a [[ReactiveClasses]] set: its `class` token plus the CSS it contributes. Both are opaque strings to
    * `core`; the css module builds these from a `CssClass` (`className` + `contributionBlocks`).
    */
  final case class ClassContribution(className: String, styleBlocks: Vector[(String, String)])

  /** A reactive SET of style classes bound to an element. On each emit the mount engine (1) records every emitted
    * class's [[ClassContribution.styleBlocks]] into the render's `StyleRegistry` — so a class's CSS reaches the
    * stylesheet the moment it first appears, no separate up-front contribution — and (2) diffs the element's `class`
    * tokens to exactly this set's [[ClassContribution.className]]s, leaving tokens contributed by sibling
    * static/other-reactive class attrs untouched (the same per-binding classList diff a reactive `class` string uses).
    *
    * This is the typed, string-free way to drive `class` from state: the css module lifts a `Squawk[Set[CssClass]]`
    * into it, so `E.li(sq.map(t => if t.done then Set(Row, Done) else Set(Row)))` both toggles the classes and carries
    * their CSS. Contributes no DOM by itself, so it's void-safe.
    */
  final case class ReactiveClasses(classes: Squawk[Set[ClassContribution]]) extends Attr[Any]

  /** Lift an [[AttrKey]] + a Scala value into a [[StaticAttr]] by running its codec.
    *
    * This is the single primitive the DSL uses to turn `A.id("x")` into a node attribute. The codec captures
    * per-attribute serialization (presence-flag booleans, integer stringification, etc.) so the lifted result is
    * uniform across attribute kinds.
    */
  def from[V](key: AttrKey[V], value: V): Attr[Any] =
    StaticAttr(key.domName, key.encode(value))

  /** Lift an [[AttrKey]] + a Squawk into a [[ReactiveAttr]] that maps the codec on each emit. */
  def fromSquawk[V](key: AttrKey[V], src: Squawk[V]): ReactiveAttr =
    ReactiveAttr(key.domName, src.map(key.encode))
end Attr
