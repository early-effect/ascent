package ascent.css

import ascent.ast.Attr
import ascent.domtypes.AttrValue
import ascent.squawk.Squawk

/** Owns one HTML attribute (typically `data-state`) tracking a [[Squawk]] of strings, and produces both the AST-side
  * [[Attr]] and CSS-side [[Selector]] for it.
  *
  * The pattern: state-driven styling needs the same attribute name to appear in two places — on the element (so the
  * browser updates the DOM) and inside a CSS selector (so the style switches with the value). Centralizing the name
  * removes one whole class of typo bugs and keeps the pair of expressions local and readable.
  *
  * Construct via the friendlier factory [[StateAttr.dataState]] when the attribute is `data-state`, or via
  * `StateAttr.apply` for a custom name.
  */
final case class StateAttr(name: String, source: Squawk[String]):
  /** The reactive attribute to attach to the element. */
  def toAttr: Attr[Any] =
    Attr.ReactiveAttr(name, source.map(s => AttrValue.Str(s)))

  /** A selector that matches when the attribute equals `value`. Nested members are declarations (or further selectors)
    * that apply only in that state.
    *
    * Renders as `[<name>='<value>']`. When nested inside a parent selector (e.g. inside a [[CssScope]] body) the parent
    * prefix concatenates directly without a descendant combinator, producing
    * `[data-ascent="..."][data-state='loading']`.
    */
  def whenIs(value: String, members: CssMember*): Selector =
    Selector(s"[$name='$value']", members*)
end StateAttr

object StateAttr:
  /** The common case: a [[StateAttr]] backed by `data-state`. */
  def dataState(source: Squawk[String]): StateAttr =
    StateAttr("data-state", source)
