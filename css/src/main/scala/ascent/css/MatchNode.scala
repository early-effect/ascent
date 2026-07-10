package ascent.css

/** The structural AST [[SelectorGrammar]] parses a CSS selector string INTO, and [[SelMatch]] evaluates against a
  * [[Matchable]] element. Distinct from [[Sel]] (the emit-only authoring fragment) because `Sel`'s `Compound` case
  * historically carried only a rendered STRING — nothing a matcher could destructure. `Sel.parse` builds one of these
  * and attaches it to the returned `Sel` (see `Sel.Compound.ast`); the authoring DSL (`.cls`, `.child`, …) never
  * populates it, since compile-time-built selectors have no need for runtime matching.
  */
sealed trait MatchNode

object MatchNode:
  /** A compound selector: an optional type/universal test, plus zero or more simple selectors (class/id/attribute/
    * pseudo-class) that ALL must match — `div.card#main[href]:hover` is one `Compound` with `typeSel = Some(Tag(
    * "div"))` and four simples. `typeSel = None` behaves as the universal selector (real CSS: `.foo` implicitly means
    * `*.foo`).
    */
  final case class Compound(typeSel: Option[TypeSel], simples: List[SimpleSel]) extends MatchNode

  /** Two selectors joined by a combinator — `a > b` is `Combined(a, Child, b)`. `right` is the compound that must match
    * the CANDIDATE element itself; `left` must match some element related to it per `combinator` (its parent, an
    * ancestor, a preceding sibling, …). Built left-associatively by the grammar, so `a b c` parses as
    * `Combined(Combined(a, Descendant, b), Descendant, c)` — matching walks outward from the rightmost compound.
    */
  final case class Combined(left: MatchNode, combinator: Combinator, right: MatchNode) extends MatchNode

  /** A selector list (`a, b, c`) — matches if ANY alternative matches. */
  final case class Or(alternatives: List[MatchNode]) extends MatchNode
end MatchNode

/** The type/universal portion of a [[MatchNode.Compound]]. */
enum TypeSel:
  case Universal
  case Tag(name: String)

/** The five CSS combinators, matching [[Sel]]'s existing binary-combinator names. `Column` (`||`) has no meaningful
  * structural semantics without real table-layout knowledge (which element structure like children/parent doesn't
  * capture) — [[SelMatch]] treats it as never-matching, a documented, deliberate simplification rather than a
  * fabricated approximation.
  */
enum Combinator:
  case Descendant, Child, NextSibling, SubsequentSibling, Column

/** A single simple selector inside a [[MatchNode.Compound]]. */
enum SimpleSel:
  case ClassSel(name: String)
  case IdSel(name: String)

  /** `test = None` is a bare presence test (`[required]`); `Some((op, expected))` carries a value comparison
    * (`[type="checkbox"]`).
    */
  case AttrSel(name: String, test: Option[(AttrOp, String)])

  /** A pseudo-class by its bare name (no leading colon) plus its parsed argument, if any. Which names [[SelMatch]]
    * evaluates structurally (vs. its universal "unknown pseudo = always matches" fallback) is decided entirely in
    * `SelMatch`, not here — this case is purely syntactic.
    */
  case PseudoSel(name: String, arg: Option[PseudoArg])
end SimpleSel

/** The parsed argument of a functional pseudo-class (`:nth-child(2n+1)`, `:not(.a, .b)`, `:lang(en)`). */
enum PseudoArg:
  case NthArg(nth: Nth)
  case SelectorListArg(alternatives: List[MatchNode])

  /** Fallback for any functional-pseudo argument that isn't an `<An+B>` micro-syntax or a selector list (e.g.
    * `:lang(en)`, `:dir(rtl)`) — captured verbatim so the grammar accepts real-world selectors using these pseudos even
    * though [[SelMatch]] has no structural semantics for them (they fall through to its always-matches fallback, same
    * as any other unrecognized pseudo-class).
    */
  case RawArg(text: String)
end PseudoArg
