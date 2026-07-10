package ascent.dsl

import ascent.ast.{Attr, UI}
import ascent.squawk.Squawk

/** What can be passed to an element constructor.
  *
  * `Arg[-R]` threads the ZIO environment its effectful parts require (via the `Attr`/`UI` it carries). Contravariant
  * like [[UI]]/[[Attr]], so a constructor's varargs infer the intersection of every arg's requirement.
  *
  * Two tiers, enforced by the type:
  *   - [[Arg]] — anything valid in a normal element: children OR attributes.
  *   - [[VoidArg]] — the subset valid on a VOID element (`br`, `input`, `img`, …): attributes and events ONLY. A void
  *     element's constructor takes `VoidArg*`, so passing a child (which lifts to a child-bearing `Arg`, never a
  *     `VoidArg`) fails to type-check.
  */
sealed trait Arg[-R]

/** The attribute-only subset of [[Arg]] — what a void element accepts. */
sealed trait VoidArg[-R] extends Arg[R]

object Arg:
  /** The empty arg — the value of `None: Option[Arg]` and a no-op separator. Contributes no DOM, so it's void-safe.
    */
  case object Empty extends VoidArg[Any]

  /** A child node in the parent's children list. NOT a [[VoidArg]] — void elements reject it. */
  final case class ChildArg[R](ui: UI[R]) extends Arg[R]

  /** An attribute on the parent element. Valid on void elements too. */
  final case class AttrArg[R](attr: Attr[R]) extends VoidArg[R]

  /** A flattenable list of args — splat a `Seq` into children/attrs. */
  final case class ArgsArg[R](args: Seq[Arg[R]]) extends Arg[R]

  /** A flattenable list of void-safe args, kept distinct from [[ArgsArg]] so an attribute-only bundle can flatten onto
    * a void element without smuggling in children.
    */
  final case class VoidArgsArg[R](args: Seq[VoidArg[R]]) extends VoidArg[R]

  // --- conversions: lift common values into Arg with no ceremony at the call site ---

  // Each given has an explicit name: without one, the synthesized name collides for types like
  // `Squawk[String]` and `Squawk[UI]` (both would derive `given_Conversion_Squawk_Arg`).

  /** A bare String becomes a Text child. */
  given stringToArg: Conversion[String, Arg[Any]] = s => ChildArg(UI.Text(s))

  /** Bare numeric / boolean values become text. */
  given intToArg: Conversion[Int, Arg[Any]]       = n => ChildArg(UI.Text(n.toString))
  given doubleToArg: Conversion[Double, Arg[Any]] = n => ChildArg(UI.Text(n.toString))
  given longToArg: Conversion[Long, Arg[Any]]     = n => ChildArg(UI.Text(n.toString))
  given boolToArg: Conversion[Boolean, Arg[Any]]  = b => ChildArg(UI.Text(b.toString))

  /** A UI is a child directly. */
  given uiToArg[R]: Conversion[UI[R], Arg[R]] = ChildArg(_)

  /** An Attr is an attribute directly — and is void-safe. */
  given attrToArg[R]: Conversion[Attr[R], VoidArg[R]] = AttrArg(_)

  /** A Squawk[String] becomes a ReactiveText child — fast-path for live text. */
  given squawkStringToArg: Conversion[Squawk[String], Arg[Any]] = s => ChildArg(UI.ReactiveText(s))

  /** A Squawk[UI] becomes a ReactiveChild — swaps a subtree on each emit. */
  given squawkUiToArg[R]: Conversion[Squawk[UI[R]], Arg[R]] = s => ChildArg(UI.ReactiveChild(s))

  /** A Seq[Arg] flattens at insertion time. */
  given seqToArg[R]: Conversion[Seq[Arg[R]], Arg[R]] = ArgsArg(_)

  /** A bundle of `Attr`s flattens into a single void-safe arg, so a helper that returns `Iterable[Attr]` can be passed
    * directly inside a constructor without `*`-splatting.
    *
    * `Iterable[Attr]` rather than `List[Attr]` so this also covers `Seq` / `Vector` returns.
    */
  given attrIterableToArg[R]: Conversion[Iterable[Attr[R]], VoidArg[R]] =
    attrs => VoidArgsArg(attrs.toList.map(AttrArg(_)))

  /** None becomes Empty; Some unwraps. */
  given optionToArg[R]: Conversion[Option[Arg[R]], Arg[R]] = _.getOrElse(Empty)

end Arg
