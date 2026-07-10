package ascent.css

/** The minimal structural surface [[Sel.matches]] / the selector matcher needs from an element-shaped value.
  *
  * `css` defines this typeclass itself rather than depending on any concrete DOM abstraction — a future cross-platform
  * DOM module's `Element`/`Document` implementations (a real browser adapter, an in-memory backend, anything else) each
  * supply a `given Matchable[E]` instance so `querySelector`-style code can delegate into this matcher with zero
  * coupling in either direction: `css` never depends on a DOM type, and a DOM module depending on `css` only needs to
  * provide one small instance, not restructure its own model.
  *
  * All operations are queries over the CURRENT structural state — no mutation, no identity beyond what `==`/`eq`
  * already give the concrete `E`.
  */
trait Matchable[E]:
  /** The element's tag name, matched against type selectors (`div`, `li`, …) and the universal selector. Comparison is
    * case-sensitive as given — callers normalizing HTML's ASCII-case-insensitive tag names should lowercase before
    * calling in, keeping this typeclass itself free of any case-folding policy.
    */
  def tagName(e: E): String

  /** The value of a single attribute, or `None` if absent — backs attribute selectors (`[href]`, `[type="checkbox"]`,
    * …). `class` and `id` are ALSO queryable as plain attributes here (in addition to [[classes]]/[[id]] below) since
    * `[class~="foo"]`-style attribute selectors are valid CSS distinct from the `.foo` shorthand.
    */
  def attr(e: E, name: String): Option[String]

  /** The element's class-list as a set of tokens — backs the `.foo` shorthand and is reused outside the matcher too
    * (e.g. a Mount engine's class-merge logic can route through the same extraction instead of re-deriving it from raw
    * attribute strings).
    */
  def classes(e: E): Set[String]

  /** The element's `id` attribute, or `None` — backs the `#foo` shorthand. */
  def id(e: E): Option[String]

  /** The element's parent, or `None` at the tree root — backs the descendant/child combinators and ancestor-relative
    * pseudo-classes.
    */
  def parent(e: E): Option[E]

  /** The element's direct children, in document order — backs the child combinator and `:has()`'s descendant search. */
  def children(e: E): Seq[E]

  /** Every EARLIER sibling under the same parent, in document order (closest-first is NOT required — see
    * [[nextSiblings]] for the ordering `:nth-child`/`:first-child` actually need). Backs `:first-child`/`:nth-child`
    * counting and the general-sibling (`~`) combinator's "any earlier sibling" search.
    */
  def previousSiblings(e: E): Seq[E]

  /** Every LATER sibling under the same parent, in document order. Backs `:last-child`/`:nth-last-child` counting, the
    * adjacent-sibling (`+`) combinator, and the general-sibling (`~`) combinator's forward search.
    */
  def nextSiblings(e: E): Seq[E]
end Matchable
