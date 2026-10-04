package ascent.dsl

import ascent.ast.{Attr, UI}
import ascent.domtypes.tags.Element
import ascent.squawk.Squawk

/** What can be passed to an element constructor.
  *
  * `Arg[-R, -E]` threads the ZIO environment its effectful parts require (via the `Attr`/`UI` it carries) and the
  * element marker those attributes are valid on. Contravariant in both, so a constructor's varargs infer the
  * intersection of every arg's requirement, and an attribute typed at `Element` is accepted by every element.
  *
  * Two tiers, enforced by the type:
  *   - [[Arg]]: anything valid in a normal element, children or attributes.
  *   - [[VoidArg]]: the subset valid on a void element (`br`, `input`, `img`): attributes and events only. A void
  *     element's constructor takes `VoidArg*`, so passing a child (which lifts to a child-bearing `Arg`, never a
  *     `VoidArg`) fails to type-check.
  */
sealed trait Arg[-R, -E]

/** The attribute-only subset of [[Arg]]. What a void element accepts. */
sealed trait VoidArg[-R, -E] extends Arg[R, E]

object Arg:
  /** The empty arg. The value of `None: Option[Arg]` and a no-op separator. Contributes no DOM, so it's void-safe.
    */
  case object Empty extends VoidArg[Any, Element]

  /** A child node in the parent's children list. Not a [[VoidArg]]: void elements reject it. Children are not
    * element-specific, so the marker is [[Element]].
    */
  final case class ChildArg[R](ui: UI[R]) extends Arg[R, Element]

  /** An untyped attribute (an event, a raw [[Attr]], a CSS contribution). Valid on every element. */
  final case class AttrArg[R](attr: Attr[R]) extends VoidArg[R, Element]

  /** An attribute whose key is valid only on element marker `E`. Invariant in `E` so the case-class `copy` stays legal;
    * the contravariance that accepts a wider marker lives on [[VoidArg]].
    */
  final case class TypedAttrArg[R, E <: Element](attr: Attr[R]) extends VoidArg[R, E]

  /** A flattenable list of args. Splat a `Seq` into children and attrs. A meta-only attribute cannot enter: the list is
    * typed at [[Element]].
    */
  final case class ArgsArg[R](args: Seq[Arg[R, Element]]) extends Arg[R, Element]

  /** A flattenable list of void-safe args, kept distinct from [[ArgsArg]] so an attribute-only bundle can flatten onto
    * a void element without smuggling in children.
    */
  final case class VoidArgsArg[R, E <: Element](args: Seq[VoidArg[R, E]]) extends VoidArg[R, E]

  // --- conversions: lift common values into Arg with no ceremony at the call site ---

  // Each given has an explicit name: without one, the synthesized name collides for types like
  // `Squawk[String]` and `Squawk[UI]` (both would derive `given_Conversion_Squawk_Arg`).

  /** A bare String becomes a Text child. */
  given stringToArg: Conversion[String, Arg[Any, Element]] = s => ChildArg(UI.Text(s))

  /** Bare numeric / boolean values become text. */
  given intToArg: Conversion[Int, Arg[Any, Element]]       = n => ChildArg(UI.Text(n.toString))
  given doubleToArg: Conversion[Double, Arg[Any, Element]] = n => ChildArg(UI.Text(n.toString))
  given longToArg: Conversion[Long, Arg[Any, Element]]     = n => ChildArg(UI.Text(n.toString))
  given boolToArg: Conversion[Boolean, Arg[Any, Element]]  = b => ChildArg(UI.Text(b.toString))

  /** A UI is a child directly. */
  given uiToArg[R]: Conversion[UI[R], Arg[R, Element]] = ChildArg(_)

  /** An Attr is an attribute directly, and is void-safe. Untyped, so every element accepts it. */
  given attrToArg[R]: Conversion[Attr[R], VoidArg[R, Element]] = AttrArg(_)

  /** A Squawk[String] becomes a ReactiveText child. Fast path for live text. */
  given squawkStringToArg: Conversion[Squawk[String], Arg[Any, Element]] = s => ChildArg(UI.ReactiveText(s))

  /** A Squawk[UI] becomes a ReactiveChild. Swaps a subtree on each emit. */
  given squawkUiToArg[R]: Conversion[Squawk[UI[R]], Arg[R, Element]] = s => ChildArg(UI.ReactiveChild(s))

  /** A Seq[Arg] flattens at insertion time. The sequence is typed at [[Element]], so a meta-only attribute cannot be
    * stuffed into it.
    */
  given seqToArg[R]: Conversion[Seq[Arg[R, Element]], Arg[R, Element]] = ArgsArg(_)

  /** A bundle of `Attr`s flattens into a single void-safe arg, so a helper that returns `Iterable[Attr]` can be passed
    * directly inside a constructor without `*`-splatting.
    *
    * `Iterable[Attr]` rather than `List[Attr]` so this also covers `Seq` / `Vector` returns.
    */
  given attrIterableToArg[R]: Conversion[Iterable[Attr[R]], VoidArg[R, Element]] =
    attrs => VoidArgsArg(attrs.toList.map(AttrArg(_)))

  /** None becomes Empty; Some unwraps. */
  given optionToArg[R]: Conversion[Option[Arg[R, Element]], Arg[R, Element]] = _.getOrElse(Empty)

end Arg
