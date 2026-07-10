package ascent.domgen.css

import fastparse.*

/** AST + parser for the CSS value-grammar mini-language used in webref's `value:` field of property definitions.
  *
  * Examples that parse:
  * {{{
  *   bolder | lighter | <font-weight-absolute>
  *   <length-percentage> | auto
  *   [ <length> | <percentage> ]{1,4}
  *   rgb( <number>#{3} )
  *   <'background-color'>
  * }}}
  *
  * Combinator precedence (loosest → tightest): `|` < `||` < `&&` < juxtaposition < multipliers / brackets / function
  * calls
  */
object CssGrammar:

  // ---------- AST ----------

  /** A CSS value-grammar node. */
  sealed trait Grammar

  /** A bare keyword, e.g. `auto` / `space-between`. */
  final case class Keyword(name: String) extends Grammar

  /** `<color>`, `<length-percentage>`, … — a type reference. */
  final case class TypeRef(name: String) extends Grammar

  /** `<'background-color'>` — a property reference (note inner quotes). */
  final case class PropertyRef(name: String) extends Grammar

  /** `[ … ]` — explicit grouping. Codegen and tests can decide whether to inline a Group's single inner node; the
    * parser preserves the wrapper to keep grammars round-trippable.
    */
  final case class Group(inner: Grammar) extends Grammar

  /** `name( body )` — function notation. The body is a full sub-grammar. */
  final case class FunctionCall(name: String, body: Grammar) extends Grammar

  /** `a | b | c` — choose exactly one. Flat list (left-assoc chains collapse). */
  final case class OneOf(alts: List[Grammar]) extends Grammar

  /** `a || b` — at least one of, in any order. Flat list. */
  final case class AnyOrderOneOrMore(alts: List[Grammar]) extends Grammar

  /** `a && b` — all of, in any order. Flat list. */
  final case class AllInAnyOrder(alts: List[Grammar]) extends Grammar

  /** Juxtaposition — items in this exact order. Flat list, length ≥ 2. */
  final case class Sequence(items: List[Grammar]) extends Grammar

  /** `expr <multiplier>` — a postfix multiplier on its operand. */
  final case class Multiplied(operand: Grammar, multiplier: Multiplier) extends Grammar

  /** A literal `/` separator inside a sequence (used in shorthand grammars like `<line-width> / <line-style>`).
    */
  case object SlashLiteral extends Grammar

  /** Postfix multiplier kinds. */
  sealed trait Multiplier
  object Multiplier:
    /** `?` — zero or one. */
    case object Optional extends Multiplier

    /** `*` — zero or more. */
    case object ZeroOrMore extends Multiplier

    /** `+` — one or more. */
    case object OneOrMore extends Multiplier

    /** `!` — required (at least one component of a group). */
    case object Required extends Multiplier

    /** `#` — comma-separated list, optional `{n,m}` range (None = no range). */
    final case class CommaList(range: Option[(Int, Option[Int])]) extends Multiplier

    /** `{n}` / `{n,m}` / `{n,}` — count or range. */
    final case class Range(min: Int, max: Option[Int]) extends Multiplier
  end Multiplier

  /** Parse a CSS value-grammar string into a [[Grammar]]. Returns the parse error message on failure (Left), so the
    * generator can surface a useful diagnostic when webref ships a grammar shape we don't yet handle.
    */
  def parse(source: String): Either[String, Grammar] =
    if source.trim.isEmpty then Left("empty grammar")
    else
      fastparse.parse(source, root(using _)) match
        case Parsed.Success(g, _) => Right(g)
        case f: Parsed.Failure    => Left(f.trace().longAggregateMsg)

  // ---------- Parser ----------
  //
  // The parser uses fastparse 3. Whitespace handling is explicit: between every interesting
  // production we consume optional spaces. Multipliers are a postfix step on the *atom*
  // (so they can attach to a Group, FunctionCall, TypeRef, PropertyRef, or Keyword).
  //
  // Combinator precedence is encoded as a layered grammar:
  //
  //   root        = alternation
  //   alternation = anyOrder ("|"  anyOrder)*       — `OneOf` if more than one
  //   anyOrder    = allInOrder ("||" allInOrder)*   — `AnyOrderOneOrMore`
  //   allInOrder  = juxtaposed ("&&" juxtaposed)*   — `AllInAnyOrder`
  //   juxtaposed  = postfix postfix*                — `Sequence`
  //   postfix     = atom multiplier?                — `Multiplied`
  //   atom        = group | function | typeRef | propertyRef | slashLit | keyword

  import fastparse.NoWhitespace.*

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t\r\n", 0))

  // -- atoms --

  /** An identifier: `[A-Za-z_][A-Za-z0-9_-]*`. */
  private def ident[$: P]: P[String] =
    P(CharIn("A-Za-z_") ~ CharsWhileIn("A-Za-z0-9_\\-", 0)).!

  /** A keyword atom. */
  private def keyword[$: P]: P[Grammar] = P(ident).map(Keyword(_))

  /** `<'name'>` — property reference. */
  private def propertyRef[$: P]: P[Grammar] =
    P("<'" ~/ CharsWhile(_ != '\'').! ~ "'>").map(PropertyRef(_))

  /** `<name>` (or with a bracket-bound range like `<integer [0,∞]>`). The whole inner content up to the *closing* `>`
    * is captured verbatim. We use `<` followed by anything that isn't `>` (or, for property refs, the `'` already
    * handled above).
    */
  private def typeRef[$: P]: P[Grammar] =
    P("<" ~ !"'" ~/ CharsWhile(_ != '>').! ~ ">").map(s => TypeRef(s.trim))

  /** `[ inner ]` — an explicit group. */
  private def group[$: P]: P[Grammar] =
    P("[" ~ ws ~ alternation ~ ws ~ "]").map(Group(_))

  /** `name( body )` — a function call. Identifier is followed (immediately, no space) by `(`.
    */
  private def functionCall[$: P]: P[Grammar] =
    P(ident ~ "(" ~ ws ~ alternation ~ ws ~ ")").map { case (n, body) => FunctionCall(n, body) }

  /** Literal `/` in a sequence. */
  private def slashLit[$: P]: P[Grammar] = P("/").map(_ => SlashLiteral)

  /** Atom: tries each shape in the right order. `functionCall` must come before `keyword` because both start with an
    * identifier — `functionCall` requires a following `(`.
    */
  private def atom[$: P]: P[Grammar] =
    P(group | functionCall | propertyRef | typeRef | slashLit | keyword)

  // -- multipliers --

  private def number[$: P]: P[Int] =
    P(CharsWhileIn("0-9", 1).!).map(_.toInt)

  /** `{n}` / `{n,m}` / `{n,}` */
  private def braceRange[$: P]: P[Multiplier.Range] =
    P("{" ~ ws ~ number ~ ws ~ ("," ~ ws ~ number.?).? ~ ws ~ "}").map {
      case (n, None)          => Multiplier.Range(n, Some(n)) // {n}
      case (n, Some(None))    => Multiplier.Range(n, None)    // {n,}
      case (n, Some(Some(m))) => Multiplier.Range(n, Some(m)) // {n,m}
    }

  /** `#`, `#{n}`, `#{n,m}` */
  private def commaList[$: P]: P[Multiplier.CommaList] =
    P("#" ~ ("{" ~ ws ~ number ~ ws ~ ("," ~ ws ~ number.?).? ~ ws ~ "}").?).map {
      case None                     => Multiplier.CommaList(None)
      case Some((n, None))          => Multiplier.CommaList(Some((n, Some(n))))
      case Some((n, Some(None)))    => Multiplier.CommaList(Some((n, None)))
      case Some((n, Some(Some(m)))) => Multiplier.CommaList(Some((n, Some(m))))
    }

  private def multiplier[$: P]: P[Multiplier] =
    P(
      P("?").map(_ => Multiplier.Optional) |
        P("*").map(_ => Multiplier.ZeroOrMore) |
        P("+").map(_ => Multiplier.OneOrMore) |
        P("!").map(_ => Multiplier.Required) |
        commaList |
        braceRange
    )

  /** `atom multiplier?` — a single atom with at most one multiplier postfix attached. */
  private def postfix[$: P]: P[Grammar] =
    P(atom ~ multiplier.?).map {
      case (a, None)    => a
      case (a, Some(m)) => Multiplied(a, m)
    }

  // -- combinators --

  /** Juxtaposition: 1+ postfixes back-to-back. Length 1 → just the atom. */
  private def juxtaposed[$: P]: P[Grammar] =
    P(postfix ~ (ws ~ !endOfClause ~ postfix).rep).map { case (first, rest) =>
      if rest.isEmpty then first else Sequence(first :: rest.toList)
    }

  /** Used to stop juxtaposition before an explicit combinator/closer. Without this guard, `postfix` would happily
    * consume a `&&` token as a (broken) keyword start, etc.
    */
  private def endOfClause[$: P]: P[Unit] =
    P("|" | "||" | "&&" | "]" | ")" | End)

  /** `a && b && c` — all in any order. */
  private def allInOrder[$: P]: P[Grammar] =
    P(juxtaposed ~ (ws ~ "&&" ~ ws ~ juxtaposed).rep).map { case (first, rest) =>
      if rest.isEmpty then first else AllInAnyOrder(first :: rest.toList)
    }

  /** `a || b || c` — any-order, one-or-more. Note: `||` must be matched BEFORE `|`. */
  private def anyOrder[$: P]: P[Grammar] =
    P(allInOrder ~ (ws ~ "||" ~ ws ~ allInOrder).rep).map { case (first, rest) =>
      if rest.isEmpty then first else AnyOrderOneOrMore(first :: rest.toList)
    }

  /** `a | b | c` — alternation. */
  private def alternation[$: P]: P[Grammar] =
    // Ensure we don't eat `||` by mistake: require that `|` is NOT immediately followed by `|`.
    P(anyOrder ~ (ws ~ "|" ~ !"|" ~ ws ~ anyOrder).rep).map { case (first, rest) =>
      if rest.isEmpty then first else OneOf(first :: rest.toList)
    }

  /** Top-level entry: optional whitespace, alternation, end-of-input. */
  private def root[$: P]: P[Grammar] =
    P(ws ~ alternation ~ ws ~ End)

end CssGrammar
