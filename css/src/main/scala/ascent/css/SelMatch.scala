package ascent.css

/** Evaluates a [[MatchNode]] (produced by [[SelectorGrammar]]) against a [[Matchable]] element — the runtime
  * counterpart to [[Sel]]'s compile-time authoring DSL. Called via the `Sel.matches` extension, not directly.
  */
private[css] object SelMatch:

  /** Pseudo-classes with no meaningful STATIC-tree semantics — browser interaction state a structural element has no
    * way to observe. Rather than always-false (which would make `a:hover` never match anything — a worse authoring
    * surprise than the alternative), these evaluate as always-TRUE: `a:hover` matches the same elements as the plain
    * `a`. Mirrors the documented precedent in zio-blocks-html's `DomSelection` for exactly this case.
    */
  private val interactionStatePseudos: Set[String] =
    Set("hover", "focus", "active", "visited", "focus-within", "focus-visible", "target", "target-within")

  def matches[E](node: MatchNode, e: E)(using m: Matchable[E]): Boolean =
    node match
      case MatchNode.Compound(typeSel, simples) =>
        matchesType(typeSel, e) && simples.forall(matchesSimple(_, e))
      case MatchNode.Combined(left, combinator, right) =>
        matches(right, e) && matchesRelated(left, combinator, e)
      case MatchNode.Or(alternatives) =>
        alternatives.exists(matches(_, e))

  private def matchesType[E](typeSel: Option[TypeSel], e: E)(using m: Matchable[E]): Boolean =
    typeSel match
      case None                    => true
      case Some(TypeSel.Universal) => true
      case Some(TypeSel.Tag(name)) => m.tagName(e) == name

  private def matchesSimple[E](simple: SimpleSel, e: E)(using m: Matchable[E]): Boolean =
    simple match
      case SimpleSel.ClassSel(name)      => m.classes(e).contains(name)
      case SimpleSel.IdSel(name)         => m.id(e).contains(name)
      case SimpleSel.AttrSel(name, test) =>
        m.attr(e, name) match
          case None        => false
          case Some(value) => test.forall { case (op, expected) => matchesAttrOp(op, value, expected) }
      case SimpleSel.PseudoSel(name, arg) => matchesPseudo(name, arg, e)

  private def matchesAttrOp(op: AttrOp, value: String, expected: String): Boolean = op match
    case AttrOp.Eq        => value == expected
    case AttrOp.Includes  => value.split("\\s+").contains(expected)
    case AttrOp.DashMatch => value == expected || value.startsWith(expected + "-")
    case AttrOp.Prefix    => value.startsWith(expected)
    case AttrOp.Suffix    => value.endsWith(expected)
    case AttrOp.Substring => value.contains(expected)

  private def matchesPseudo[E](name: String, arg: Option[PseudoArg], e: E)(using m: Matchable[E]): Boolean =
    (name, arg) match
      case (n, None) if interactionStatePseudos.contains(n) => true
      case ("first-child", None)                            => m.previousSiblings(e).isEmpty
      case ("last-child", None)                             => m.nextSiblings(e).isEmpty
      case ("only-child", None)                       => m.previousSiblings(e).isEmpty && m.nextSiblings(e).isEmpty
      case ("empty", None)                            => m.children(e).isEmpty
      case ("root", None)                             => m.parent(e).isEmpty
      case ("nth-child", Some(PseudoArg.NthArg(nth))) =>
        matchesNth(nth, m.previousSiblings(e).size + 1)
      case ("nth-last-child", Some(PseudoArg.NthArg(nth))) =>
        matchesNth(nth, m.nextSiblings(e).size + 1)
      case ("not", Some(PseudoArg.SelectorListArg(alts))) =>
        !alts.exists(matches(_, e))
      case ("is", Some(PseudoArg.SelectorListArg(alts))) =>
        alts.exists(matches(_, e))
      case ("where", Some(PseudoArg.SelectorListArg(alts))) =>
        alts.exists(matches(_, e))
      case ("has", Some(PseudoArg.SelectorListArg(alts))) =>
        m.children(e).exists(child => alts.exists(matches(_, child)))
      // Every other pseudo-class (nth-of-type, lang, dir, state, or any name this evaluator doesn't
      // recognize) — always matches. Structural completeness over invented semantics: a name we
      // don't model shouldn't silently exclude an otherwise-matching element.
      case _ => true

  /** `An+B` test: does `1based` (the element's 1-based position among its counted siblings) satisfy `index = a*n + b`
    * for some non-negative integer `n`?
    */
  private def matchesNth(nth: Nth, oneBased: Int): Boolean =
    if nth.a == 0 then oneBased == nth.b
    else
      val n = (oneBased - nth.b).toDouble / nth.a
      n >= 0 && n == n.floor

  private def matchesRelated[E](left: MatchNode, combinator: Combinator, e: E)(using m: Matchable[E]): Boolean =
    combinator match
      case Combinator.Child =>
        m.parent(e).exists(matches(left, _))
      case Combinator.Descendant =>
        ancestors(e).exists(matches(left, _))
      case Combinator.NextSibling =>
        m.previousSiblings(e).lastOption.exists(matches(left, _))
      case Combinator.SubsequentSibling =>
        m.previousSiblings(e).exists(matches(left, _))
      case Combinator.Column =>
        // No meaningful structural semantics without real table-layout knowledge — documented,
        // deliberate simplification (see Combinator.Column's own scaladoc).
        false

  private def ancestors[E](e: E)(using m: Matchable[E]): LazyList[E] =
    LazyList.unfold(e)(m.parent(_).map(p => (p, p)))
end SelMatch
