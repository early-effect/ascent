package ascent.css

import ascent.ast.{AstId, Attr, UI}
import ascent.dsl.{Arg, VoidArg}
import zio.*

/** Component-isolated styles keyed to a UI subtree's structural id.
  *
  * Sibling primitive to [[CssClass]]. Where `CssClass` produces selectors prefixed with its auto-derived class name
  * (`.cls-foo`), `CssScope` produces selectors prefixed with a `[data-ascent="<rootId>"]` attribute selector derived
  * from `astRoot`'s structural id.
  *
  * The unlock: the structural id is a join key between a UI value and the DOM element it mounts to. Define styles
  * against an AST value at definition time and they'll match the mounted element at runtime — without ever wiring an
  * explicit className through.
  *
  * Example — descendant rules under a component root:
  * {{{
  *   val root = E.div(...)  // hold the AST value
  *   object styles extends CssScope(root,
  *     Selector(" h1",     fontSize.px(24)),   // [data-ascent="<rootId>"] h1 { ... }
  *     Selector(" .item",  color("blue")),    // [data-ascent="<rootId>"] .item { ... }
  *   )
  * }}}
  *
  * Direct rules on the scope root (no descendant combinator):
  * {{{
  *   object titleStyles extends CssScope(titleAst,
  *     Declaration("font-size", "24px"),       // [data-ascent="<titleId>"] { ... }
  *   )
  * }}}
  */
abstract class CssScope(astRoot: UI[?], members: CssMember*):
  /** The structural id of `astRoot`, computed once at construction. */
  final val rootId: Long = AstId.compute(astRoot)

  /** The attribute-selector prefix that every rule in this scope is rooted under. */
  final val rootSelector: String = s"""[data-ascent="${AstId.renderAttr(rootId)}"]"""

  /** Render the full CSS for this scope, scoped under the data-ascent attribute selector. */
  final def renderCss: String =
    Selector(rootSelector, members*).render

  /** Inject this scope's CSS into the given sink. Idempotent under the same scope value: the sink replaces the prior
    * entry under this scope's key.
    */
  final def installInto(sink: StyleSink): UIO[Unit] =
    sink.append(sinkKey, renderCss)

  /** The key under which this scope's CSS is registered in a [[StyleSink]]. */
  final def sinkKey: String = s"scope-${AstId.renderAttr(rootId)}"

  /** This scope's contribution: `(scope-<id> -> css)` plus any `@keyframes` its rules [[Keyframes.use]]. The mount
    * engine collects these into the render's [[StyleRegistry]] when the scope is applied to an element.
    */
  final def contributionBlocks: Vector[(String, String)] =
    val keyframes = members.flatMap(_.referencedKeyframes).distinctBy(_.name).flatMap(_.contributionBlocks)
    (keyframes :+ (sinkKey -> renderCss)).toVector
end CssScope

object CssScope:

  /** Apply a `CssScope` to an element — `E.div(myScope, ...)` — contributing its CSS (as an [[ascent.ast.Attr.Style]]
    * the mount engine collects into the render's [[StyleRegistry]]). Contributes no DOM; the scope's selector already
    * targets the element via its structural id. Void-safe.
    */
  given cssScopeToArg: Conversion[CssScope, VoidArg[Any]] = scope => Arg.AttrArg(Attr.Style(scope.contributionBlocks))
