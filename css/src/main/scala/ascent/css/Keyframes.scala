package ascent.css

import ascent.ast.Attr
import ascent.css.StylesFoundation.formatDouble
import ascent.dsl.{Arg, VoidArg}
import zio.*

/** A single keyframe stop in a [[Keyframes]] animation: one or more stop labels (`from`, `to`, `<n>%`) sharing a common
  * declaration body.
  *
  * Construct via the [[Frame]] companion's helpers — [[Frame.from]], [[Frame.to]], [[Frame.pct]], and [[Frame.at]] for
  * explicit multi-stop frames.
  */
final case class Frame(stops: List[String], declarations: List[Declaration]):
  /** Render this frame as `<stops> { <decls> }` (with a trailing newline). */
  private[css] def render: String =
    val head = stops.mkString(", ")
    val body = declarations.map(d => s"  ${d.render}").mkString("\n")
    s"  $head {\n  $body\n  }\n"

object Frame:
  /** The `from` stop (semantically `0%`). */
  def from(decls: Declaration*): Frame = Frame(List("from"), decls.toList)

  /** The `to` stop (semantically `100%`). */
  def to(decls: Declaration*): Frame = Frame(List("to"), decls.toList)

  /** A percentage stop. Renders integer values as `<n>%` (CSS keyframe convention) and non-integer values via the
    * platform-stable [[StylesFoundation.formatDouble]] so the rendered output round-trips identically on JVM/JS/Native.
    */
  def pct(n: Double)(decls: Declaration*): Frame =
    val label = if n == n.toInt.toDouble then s"${n.toInt}%" else s"${formatDouble(n)}%"
    Frame(List(label), decls.toList)

  /** Multi-stop frame: e.g. `Frame.at(List("0%", "100%"))(opacity(0.5))` produces `0%, 100% { opacity: 0.5; }`.
    */
  def at(stops: List[String])(decls: Declaration*): Frame =
    Frame(stops, decls.toList)
end Frame

/** A named CSS animation timeline, paired with a [[StyleSink]] for injection.
  *
  * Sibling primitive to [[CssClass]] (which produces `.<class> { ... }`) and [[CssScope]] (which produces
  * `[data-ascent="<id>"] { ... }`). [[Keyframes]] produces an `@keyframes <name> { <frames> }` block; the `name` is
  * what you embed in an `animation: <name> ...` declaration.
  *
  * Like [[CssClass]], the `name` is AUTO-DERIVED from the object's Scala class name by default — `object TitleGlow
  * extends Keyframes(...)` renders `@keyframes App-TitleGlow` — so the animation name can't drift from or collide with
  * a sibling's. Pass an explicit name only when the CSS identifier must be a specific string (interop with a
  * hand-written stylesheet); the everyday case omits it.
  *
  * Authoring:
  * {{{
  *   object Pulse extends Keyframes(
  *     Frame.from(Declaration("transform", "scale(1)")),
  *     Frame.pct(50)(Declaration("transform", "scale(1.05)")),
  *     Frame.to(Declaration("transform", "scale(1)")),
  *   )
  *
  *   object Card extends CssClass(Pulse.use(Time.s(0.6), iterations = Some(SingleAnimationIterationCount.Infinite)))
  * }}}
  *
  * `toString` returns the animation name so it interpolates cleanly into an `animation:` shorthand string.
  */
abstract class Keyframes(nameOverride: Option[String], frames: Frame*):
  self =>

  /** Explicit-name constructor — for a specific CSS identifier (stylesheet interop). */
  def this(name: String, frames: Frame*) = this(Some(name), frames*)

  /** Auto-derived name constructor — the everyday case; the `@keyframes` id comes from the Scala object name. */
  def this(frames: Frame*) = this(scala.None, frames*)

  /** The animation's CSS name: the explicit override if given, else derived from the Scala class name (same scheme as
    * [[CssClass.className]], so it's stable across runs and unique per object).
    */
  final val name: String = nameOverride.getOrElse(CssClass.deriveClassName(self.getClass.getName))

  /** Render the full `@keyframes` rule. */
  final def renderRule: String =
    val body = frames.map(_.render).mkString
    s"@keyframes $name {\n$body}\n"

  /** The key under which this animation's rule is recorded in a [[StyleSink]] / [[StyleRegistry]]. */
  final def sinkKey: String = s"keyframes-$name"

  /** This animation's contribution: `(keyframes-<name> -> @keyframes rule)`. A [[CssClass]] that [[use]]s this keyframe
    * folds it into the class's own contribution, so the `@keyframes` block reaches the same render.
    */
  final def contributionBlocks: Vector[(String, String)] = Vector(sinkKey -> renderRule)

  /** A TYPED `animation:` shorthand referencing this keyframe — `Pulse.use(Time.s(0.4))`,
    * `TitleGlow.use(Time.s(4), iterations = SingleAnimationIterationCount.Infinite)`,
    * `FadeSlideIn.use(Time.s(0.35), fill = SingleAnimationFillMode.Both)`. Renders `animation: <name> <duration>
    * <timing> [delay] [iterations] [direction] [fill];`, omitting parts left at their CSS defaults.
    *
    * Prefer this over the string form ([[use(rest:String)*]]): the [[Time]] / [[TimingFunction]] / generated keyword
    * enums can't be mistyped, and like the string form it records the keyframe dependency AS DATA (via
    * [[Declaration.keyframe]]), so the class carrying it collects the `@keyframes` block into the render automatically.
    */
  final def use(
      duration: Time,
      timing: TimingFunction = TimingFunction.ease,
      delay: Option[Time] = scala.None,
      iterations: Option[SingleAnimationIterationCount] = scala.None,
      direction: Option[SingleAnimationDirection] = scala.None,
      fill: Option[SingleAnimationFillMode] = scala.None,
  ): Declaration =
    // Grammar order: duration timing-function delay iteration-count direction fill-mode. duration + timing are always
    // present (so a leading delay can't be misread as the first <time>); the rest render only when supplied.
    val parts = List(
      Some(duration.render),
      Some(timing.render),
      delay.map(_.render),
      iterations.map(_.render),
      direction.map(_.render),
      fill.map(_.render),
    ).flatten
    Declaration("animation", (name :: parts).mkString(" "), keyframe = Some(this))
  end use

  /** Escape-hatch `animation:` shorthand from a raw string — `Pulse.use("0.6s ease infinite")` → `animation: pulse 0.6s
    * ease infinite;`. Prefer the typed [[use(duration:ascent\.css\.Time*]] overload; this stays for shorthand grammar
    * the typed surface doesn't model. Still records the keyframe dependency as data.
    */
  final def use(rest: String): Declaration =
    Declaration("animation", s"$name $rest", keyframe = Some(this))

  /** Inject this animation's rule into the given sink, keyed by `keyframes-<name>`. Idempotent under the same name (the
    * sink replaces the entry).
    */
  final def installInto(sink: StyleSink): UIO[Unit] =
    sink.append(sinkKey, renderRule)

  /** Returns just the animation name — so `s"$Pulse 0.6s"` interpolates as `"pulse 0.6s"` for embedding in an
    * `animation:` shorthand declaration.
    */
  override def toString: String = name
end Keyframes

object Keyframes:
  /** Touch a `Keyframes` from an element — `E.div(Pulse, ...)` — to contribute its `@keyframes` rule to the render
    * without applying a class. Usually unneeded: a [[CssClass]] that [[Keyframes.use]]s the animation pulls it in
    * automatically. Contributes no DOM; void-safe.
    */
  given keyframesToArg: Conversion[Keyframes, VoidArg[Any]] = kf => Arg.AttrArg(Attr.Style(kf.contributionBlocks))
end Keyframes
