package ascent.css

import fastparse.*
import fastparse.NoWhitespace.*

/** Parses a CSS3 selector string directly into the structural [[MatchNode]] AST — mirrors `ascent.domgen.css
  * .CssGrammar`'s idiom (explicit `NoWhitespace`, a manually-threaded `ws` combinator, layered precedence functions) so
  * anyone who's read that grammar recognizes this one's shape immediately.
  *
  * Grammar (loosest to tightest):
  * {{{
  *   selectorList = complex ("," ws complex)*                    -- MatchNode.Or
  *   complex      = compound (combinator compound)*               -- MatchNode.Combined, left-associative
  *   compound     = (typeSel | universal)? simple*                -- MatchNode.Compound
  *   simple       = classSel | idSel | attrSel | pseudoClass
  * }}}
  *
  * Interaction-state pseudo-classes (`:hover`, `:focus`, `:active`, `:visited`, `:focus-within`, `:focus-visible`) and
  * any other unrecognized pseudo-class PARSE successfully as a bare [[SimpleSel.PseudoSel]] — so real-world selector
  * strings copy-pasted from a stylesheet don't hard-fail — but [[SelMatch]], not this grammar, decides they always
  * match (mirroring zio-blocks-html's `DomSelection` precedent for exactly this case).
  */
object SelectorGrammar:

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t\r\n", 0))

  // -- identifiers --

  private def ident[$: P]: P[String] =
    P(CharIn("A-Za-z_\\-") ~ CharsWhileIn("A-Za-z0-9_\\-", 0)).!

  // -- simple selectors --

  private def classSel[$: P]: P[SimpleSel] = P("." ~ ident).map(SimpleSel.ClassSel(_))
  private def idSel[$: P]: P[SimpleSel]    = P("#" ~ ident).map(SimpleSel.IdSel(_))

  private def attrOp[$: P]: P[AttrOp] = P(
    "~=".!.map(_ => AttrOp.Includes) | "|=".!.map(_ => AttrOp.DashMatch) |
      "^=".!.map(_ => AttrOp.Prefix) | "$=".!.map(_ => AttrOp.Suffix) |
      "*=".!.map(_ => AttrOp.Substring) | "=".!.map(_ => AttrOp.Eq)
  )

  private def quotedValue[$: P]: P[String] =
    P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\"") | P("'" ~ CharsWhile(_ != '\'', 0).! ~ "'")

  /** An unquoted attribute value: `[type=checkbox]`. Real CSS restricts this to a valid identifier; we accept the same
    * `ident` shape, which covers the realistic cases without a separate token class.
    */
  private def unquotedValue[$: P]: P[String] = ident

  private def attrSel[$: P]: P[SimpleSel] =
    P("[" ~ ws ~ ident ~ ws ~ (attrOp ~ ws ~ (quotedValue | unquotedValue)).? ~ ws ~ "]").map {
      case (name, None)          => SimpleSel.AttrSel(name, None)
      case (name, Some((op, v))) => SimpleSel.AttrSel(name, Some((op, v)))
    }

  private def nth[$: P]: P[Nth] = P(
    "odd".!.map(_ => Nth.odd) | "even".!.map(_ => Nth.even) |
      (("-".!.? ~ CharsWhileIn("0-9", 0).! ~ "n" ~ ws ~ ("+" | "-").!.? ~ ws ~ CharsWhileIn("0-9", 0).!.?)
        .map { case (neg, coeff, sign, b) =>
          val a  = (if neg.isDefined then -1 else 1) * (if coeff.isEmpty then 1 else coeff.toInt)
          val bb = sign
            .map(sg => (if sg == "-" then -1 else 1) * b.filter(_.nonEmpty).map(_.toInt).getOrElse(0))
            .getOrElse(0)
          Nth(a, bb)
        }) |
      CharsWhileIn("0-9", 1).!.map(s => Nth(0, s.toInt))
  )

  private def selectorArgList[$: P]: P[List[MatchNode]] = P(complex.rep(min = 1, sep = "," ~ ws)).map(_.toList)

  private def pseudoArg[$: P]: P[PseudoArg] =
    P(nth.map(PseudoArg.NthArg(_)) | selectorArgList.map(PseudoArg.SelectorListArg(_)) | rawArg)

  /** Fallback pseudo-argument: any run of non-`)` characters, for functional pseudos with no structural meaning
    * (`:lang(en)`, `:dir(rtl)`, `:state(checked)`) — must come last since it accepts input the more specific
    * alternatives above would also match, and fastparse tries alternatives in order.
    */
  private def rawArg[$: P]: P[PseudoArg] = P(CharsWhile(_ != ')', 1).!).map(PseudoArg.RawArg(_))

  private def pseudoClass[$: P]: P[SimpleSel] =
    P(":" ~ ident ~ ("(" ~ ws ~ pseudoArg ~ ws ~ ")").?).map { case (name, arg) =>
      SimpleSel.PseudoSel(name, arg)
    }

  private def simple[$: P]: P[SimpleSel] = P(classSel | idSel | attrSel | pseudoClass)

  // -- compound + combinators --

  private def universal[$: P]: P[TypeSel] = P("*").map(_ => TypeSel.Universal)
  private def typeSel[$: P]: P[TypeSel]   = P(ident).map(TypeSel.Tag(_))

  /** A compound MUST consume at least one real component — a type/universal test, or one simple selector. Without this,
    * `(universal | typeSel).? ~ simple.rep` can match ZERO characters (both parts optional/repeatable-zero), which
    * would silently let a bare combinator boundary (`> div`, `div >`, `div >> span`) parse as if an empty compound sat
    * on either side of it. Requiring `min = 1` total components makes those genuinely fail to parse.
    */
  private def compoundWithType[$: P]: P[MatchNode] =
    P((universal | typeSel) ~ simple.rep).map { case (t, simples) => MatchNode.Compound(Some(t), simples.toList) }

  private def compoundNoType[$: P]: P[MatchNode] =
    P(simple.rep(min = 1)).map(simples => MatchNode.Compound(None, simples.toList))

  private def compound[$: P]: P[MatchNode] = P(compoundWithType | compoundNoType)

  private def combinator[$: P]: P[Combinator] =
    P(ws ~ ">" ~ ws).map(_ => Combinator.Child) |
      P(ws ~ "||" ~ ws).map(_ => Combinator.Column) |
      P(ws ~ "+" ~ ws).map(_ => Combinator.NextSibling) |
      P(ws ~ "~" ~ ws).map(_ => Combinator.SubsequentSibling) |
      P(CharsWhileIn(" \t\r\n", 1)).map(_ => Combinator.Descendant)

  private def complex[$: P]: P[MatchNode] =
    P(compound ~ (combinator ~ compound).rep).map { case (first, rest) =>
      rest.foldLeft(first) { case (acc, (comb, next)) => MatchNode.Combined(acc, comb, next) }
    }

  private def selectorList[$: P]: P[MatchNode] =
    P(complex ~ (ws ~ "," ~ ws ~ complex).rep).map { case (first, rest) =>
      if rest.isEmpty then first else MatchNode.Or(first :: rest.toList)
    }

  private def root[$: P]: P[MatchNode] = P(ws ~ selectorList ~ ws ~ End)

  /** Parse `source` into a [[MatchNode]], or the fastparse failure trace as a diagnostic message. */
  def parse(source: String): Either[String, MatchNode] =
    if source.trim.isEmpty then Left("empty selector")
    else
      fastparse.parse(source, root(using _)) match
        case Parsed.Success(node, _) => Right(node)
        case f: Parsed.Failure       => Left(f.trace().longAggregateMsg)
end SelectorGrammar
