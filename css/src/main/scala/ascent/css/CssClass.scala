package ascent.css

import ascent.ast.Attr
import ascent.domtypes.AttrValue
import ascent.dsl.{Arg, VoidArg}
import zio.*

/** A scoped CSS rule set bound to an auto-generated class name. Extend it, pass declarations / nested selectors to the
  * constructor, and apply it to an element directly — `E.div(Card, "hello")`.
  *
  * Styles auto-bootstrap (see [[StyleRegistry]]); [[installInto]] a [[StyleSink]] remains for SSR or explicit control.
  *
  * Example:
  * {{{
  *   object Card extends CssClass(
  *     padding("8px"),
  *     Selector(":hover", color("blue")),
  *   )
  *   E.div(Card, "hello")
  * }}}
  */
abstract class CssClass(members: CssMember*):
  self =>

  /** Stable auto-derived class name. Same Scala class → same name across runs. */
  final val className: String = CssClass.deriveClassName(self.getClass.getName)

  /** Render the full CSS for this class, scoped under `.<className>`. */
  final def renderCss: String =
    val rule = Selector(s".$className", members*)
    rule.render

  /** Every CSS block this class contributes to a render: each `@keyframes` it [[Keyframes.use]]s (so an animated class
    * carries its animation), then the class rule itself. `[[CssClass.cssClassToArg]]` puts these on the element as an
    * [[ascent.ast.Attr.Style]]; the mount engine collects them into the render's [[StyleRegistry]]. Keyframes first so
    * an `@keyframes` block precedes the rule that references it (order-independent for matching, but tidy output).
    */
  final def contributionBlocks: Vector[(String, String)] =
    val keyframes = members.flatMap(_.referencedKeyframes).distinctBy(_.name).flatMap(_.contributionBlocks)
    (keyframes :+ (className -> renderCss)).toVector

  /** Inject this class's CSS into the given sink. Idempotent: calling twice with the same sink is harmless (the sink
    * replaces the existing entry under [[className]]).
    */
  final def installInto(sink: StyleSink): UIO[Unit] =
    sink.append(className, renderCss)

  /** Lift this class to a `class` attribute. Usually unneeded — the [[CssClass.cssClassToArg]] conversion lets you pass
    * the class directly, `E.div(MyClass, ...)`; reach for `toAttr` only to compose into an attribute bundle.
    */
  final def toAttr: Attr[Any] = Attr.StaticAttr("class", AttrValue.Str(className))

  /** Contribute this class's CSS to the render WITHOUT applying the `class` attribute. Use it when the class NAME is
    * applied dynamically — a reactive `class` squawk (`A.className(sq)`), or imperatively via `classList` in an event
    * handler — so its stylesheet still reaches the render even though `E.div(MyClass, …)` never carried it. Put it on
    * the element that will (or may) wear the class: `E.li(Dragging.contribute, A.className(reactiveClass), …)`.
    */
  final def contribute: Attr[Any] = Attr.Style(contributionBlocks)

  /** This class as a [[ascent.ast.Attr.ClassContribution]] — its `class` token plus its CSS — for a reactive class set
    * ([[CssClass.classesToArg]]). Rarely built by hand.
    */
  private[css] def asContribution: Attr.ClassContribution = Attr.ClassContribution(className, contributionBlocks)
end CssClass

object CssClass:

  /** Pass a `CssClass` straight to an element: `E.div(Card, ...)`. Contributes BOTH the `class` attribute and the
    * class's CSS (as an [[ascent.ast.Attr.Style]] the mount engine collects into the render's [[StyleRegistry]]).
    * Void-safe — both parts are attributes, so `E.input(Card)` type-checks.
    */
  given cssClassToArg: Conversion[CssClass, VoidArg[Any]] = cc =>
    Arg.VoidArgsArg(Seq(Arg.AttrArg(cc.toAttr), Arg.AttrArg(Attr.Style(cc.contributionBlocks))))

  /** Drive the `class` attribute from state, string-free: pass a `Squawk[Set[CssClass]]` straight to an element —
    * `E.li(item.map(t => if t.done then Set(Row, Done) else Set(Row)), …)`. On each emit the mount engine toggles the
    * `class` tokens to exactly this set AND records each class's CSS into the render (idempotent), so no separate
    * `.contribute` and no `.className` string-building. Void-safe. See [[ascent.ast.Attr.ReactiveClasses]].
    */
  given classesToArg: Conversion[ascent.squawk.Squawk[Set[CssClass]], VoidArg[Any]] = src =>
    Arg.AttrArg(Attr.ReactiveClasses(src.map(_.map(_.asContribution))))

  /** Per-position styles targeting a specific AST node's structural id.
    *
    * Value-form sibling of `object x extends CssScope(node, ...)`. The selectors are the same —
    * `[data-ascent="<id>"] ...` — but you get a value to pass around instead of a singleton to extend. Useful when the
    * styles are built conditionally or close to the element's definition site.
    *
    * Example:
    * {{{
    *   val title    = E.h1("Hello")
    *   val styles   = CssClass.targeting(title,
    *     Declaration("font-size", "24px"),
    *     Selector(":hover", Declaration("color", "blue")),
    *   )
    *   styles.installInto(sink)
    * }}}
    */
  def targeting(astNode: ascent.ast.UI[?], members: CssMember*): CssScope =
    new CssScope(astNode, members*) {}

  /** Build a CSS-safe identifier from a Scala class name.
    *
    * Strips trailing `$` (Scala emits these for objects), replaces non-word chars with `-`, removes any trailing `-`.
    * Result: `MyApp$styles$Toolbar$` → `MyApp-styles-Toolbar`. Two distinct classes produce distinct names; the same
    * class is stable across calls.
    */
  private[css] def deriveClassName(rawClassName: String): String =
    val stripped  = rawClassName.stripSuffix("$").reverse.dropWhile(_ == '$').reverse
    val sanitized = stripped.replaceAll("[^A-Za-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    if sanitized.isEmpty || !sanitized.head.isLetter then "ascent-" + sanitized
    else sanitized
end CssClass
