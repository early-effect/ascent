package ascent.css

import ascent.ast.Attr
import ascent.dsl.{Arg, VoidArg}
import zio.*

/** A single document-global CSS block: a sink key plus its rendered text.
  *
  * Membership in a [[GlobalStyle]] is what marks a block page-global — so a dual-role [[MediaQuery]]/[[SupportsQuery]]
  * (legal both nested in a [[CssClass]] and standalone) declares intent by where it's used: nested it renders inside
  * the class, passed straight to a [[GlobalStyle]] (it lifts via [[GlobalRule.atRuleToGlobalRule]]) it's a top-level
  * block.
  */
final case class GlobalRule(key: String, css: String)

object GlobalRule:
  /** A genuinely-raw, hand-keyed block — for CSS the typed surface can't model. A typed [[Selector]] (`body`, `*`,
    * `:focus-visible`) or a standalone at-rule ([[MediaQuery]]/[[SupportsQuery]]/[[ContainerQuery]]) should be passed
    * DIRECTLY (they lift via the conversions below) or built with [[selector]]/[[atRule]] — not rendered to a string
    * and wrapped here.
    */
  def raw(key: String, css: String): GlobalRule = GlobalRule(key, css)

  /** A typed document-level rule from a [[Sel]]: `GlobalRule.selector(Elem.body)(margin.zero, …)` → `body { … }`. Keyed
    * off the selector text, like the conversion.
    */
  def selector(sel: Sel)(members: CssMember*): GlobalRule = selectorToGlobalRule(Selector(sel, members*))

  /** As [[selector(sel:ascent\.css\.Sel*]] but with an explicit, stable dedup key independent of the selector text. */
  def selector(key: String, sel: Sel)(members: CssMember*): GlobalRule = GlobalRule(key, Selector(sel, members*).render)

  /** A standalone at-rule with an explicit dedup key: `GlobalRule.atRule("reduced-motion", reducedMotionRule)`. */
  def atRule(key: String, block: AtRuleBlock): GlobalRule = GlobalRule(key, block.render)

  given fontFaceToGlobalRule: Conversion[FontFace, GlobalRule]         = ff => GlobalRule(ff.sinkKey, ff.render)
  given pageToGlobalRule: Conversion[Page, GlobalRule]                 = p => GlobalRule(p.sinkKey, p.render)
  given counterStyleToGlobalRule: Conversion[CounterStyle, GlobalRule] = c => GlobalRule(c.sinkKey, c.render)
  given keyframesToGlobalRule: Conversion[Keyframes, GlobalRule] = k => GlobalRule(s"keyframes-${k.name}", k.renderRule)

  /** Lift a typed [[Selector]] straight into a page-global rule, keyed off the selector string — so identical rules
    * dedup in [[StyleRegistry]]. This is what lets `GlobalStyle(Selector(Elem.body, …), …)` work without a hand-rolled
    * `raw(key, sel.render)`.
    */
  given selectorToGlobalRule: Conversion[Selector, GlobalRule] = s => GlobalRule(s.selector, s.render)

  /** Lift a standalone at-rule ([[MediaQuery]]/[[SupportsQuery]]/[[ContainerQuery]]) into a page-global rule. Keyed off
    * the rendered text (no `sinkKey` on an at-rule); use [[atRule]] for a short, edit-stable key.
    */
  given atRuleToGlobalRule: Conversion[AtRuleBlock, GlobalRule] = b => GlobalRule(b.render, b.render)
end GlobalRule

/** Page-level chrome — the document-global sibling of [[CssClass]]. Carries blocks that belong to the document as a
  * whole: `@font-face`, `@page`, `@counter-style`, standalone `@media`/`@supports`, and raw top-level rules like
  * `body { ... }`.
  *
  * Constructing one registers every block, so it auto-bootstraps like the scoped primitives. Page chrome attaches to no
  * element, so declare it on the root — `E.body(PageChrome, ...)` — where passing it runs the constructor:
  * {{{
  *   object PageChrome extends GlobalStyle(
  *     Selector(Elem.body, margin.zero),     // typed Selector lifts straight in
  *     myFontFace,
  *   )
  *   def root = E.body(PageChrome, appShell(...))
  * }}}
  */
abstract class GlobalStyle(rules: GlobalRule*):

  final def globalRules: Seq[GlobalRule] = rules

  /** Every block this page chrome contributes: one `(key -> css)` per [[GlobalRule]].
    * `[[GlobalStyle.globalStyleToArg]]` puts these on the root element as an [[ascent.ast.Attr.Style]]; the mount
    * engine collects them into the render's [[StyleRegistry]].
    */
  final def contributionBlocks: Vector[(String, String)] = rules.map(r => r.key -> r.css).toVector

  /** Inject every block into `sink` — for SSR or explicit control, mirroring [[CssClass.installInto]]. */
  final def installInto(sink: StyleSink): UIO[Unit] =
    ZIO.foreachDiscard(rules)(r => sink.append(r.key, r.css))
end GlobalStyle

object GlobalStyle:

  /** Declare page chrome on an element — `E.body(PageChrome, ...)`. Contributes no DOM; it carries the chrome's blocks
    * as an [[ascent.ast.Attr.Style]] the mount engine collects into the render's [[StyleRegistry]].
    */
  given globalStyleToArg: Conversion[GlobalStyle, VoidArg[Any]] = gs => Arg.AttrArg(Attr.Style(gs.contributionBlocks))
end GlobalStyle
