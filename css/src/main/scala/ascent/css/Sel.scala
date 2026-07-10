package ascent.css

/** A typed CSS **selector fragment** — the building block of [[Selector]] rules, replacing stringly selectors like
  * `Selector(":hover", …)` / `Selector(s" .${X.className}", …)`.
  *
  * A `Sel` renders to a selector string with **no leading combinator** (so it composes onto a parent path exactly the
  * way the old hand-written strings did). Build one from a class / id / type / attribute / pseudo, then refine it
  * fluently:
  * {{{
  *   cls(Card).id("main").pseudoClass(PseudoClass.hover)   // .card#main:hover
  *   cls("row").child(cls("cell"))                          // .row > .cell
  *   tag("li").pseudoClass(PseudoClass.nthChild(Nth.odd))   // li:nth-child(odd)
  * }}}
  *
  * **Combinators** (all five from the CSS spec) come in two flavours:
  *   - *binary* — between two fragments: `a.descendant(b)`, `a.child(b)`, `a.nextSibling(b)`, `a.subsequentSibling(b)`,
  *     `a.column(b)`;
  *   - *relative* — parent-implicit, for use as a NESTED selector inside a rule body, where the engine prepends the
  *     ancestor path: `Sel.descendant(tag("canvas"))` renders ` canvas`, so nested under `.toggle-all` it becomes
  *     `.toggle-all canvas` (the old `Selector(" canvas", …)` leading-space convention, now typed).
  *
  * `PseudoClass` and `PseudoElement` ARE `Sel`s, so a bare `PseudoClass.hover` is usable anywhere a `Sel` is — that's
  * what makes `Selector(Cls(x), PseudoClass.hover)(…)` type-check with no conversions.
  *
  * Escape hatch: [[Sel.raw]] wraps an arbitrary selector string for grammars the typed surface doesn't model (deeply
  * nested or exotic combinations).
  */
sealed trait Sel:
  /** The rendered selector string for this fragment — no leading combinator. */
  private[css] def rendered: String

  /** Compound (no combinator) — append a class to this fragment: `cls("a").cls("b")` → `.a.b`. */
  def cls(name: String): Sel = Sel.compound(rendered + "." + name)

  /** Compound — append a [[CssClass]]'s class: `tag("li").cls(Done)` → `li.done`. */
  def cls(c: CssClass): Sel = cls(c.className)

  /** Compound — append an id: `cls("a").id("main")` → `.a#main`. */
  def id(name: String): Sel = Sel.compound(rendered + "#" + name)

  /** Compound — append a presence attribute selector: `tag("input").attr("required")` → `input[required]`. */
  def attr(name: String): Sel = Sel.compound(rendered + s"[$name]")

  /** Compound — append a value attribute selector: `tag("a").attr("href", AttrOp.Prefix, "https")` →
    * `a[href^="https"]`.
    */
  def attr(name: String, op: AttrOp, value: String): Sel =
    Sel.compound(rendered + s"""[$name${op.render}"$value"]""")

  /** Compound — append a pseudo-class: `cls("a").pseudoClass(PseudoClass.hover)` → `.a:hover`. */
  def pseudoClass(pc: PseudoClass): Sel = Sel.compound(rendered + pc.render)

  /** Compound — append a pseudo-element: `cls("a").pseudoElement(PseudoElement.before)` → `.a::before`. */
  def pseudoElement(pe: PseudoElement): Sel = Sel.compound(rendered + pe.render)

  /** Compound — append any fragment with no combinator: `tag("a").and(PseudoClass.hover)` → `a:hover`. */
  def and(other: Sel): Sel = Sel.compound(rendered + other.rendered)

  /** Descendant combinator (` `): `cls("a").descendant(cls("b"))` → `.a .b`. */
  def descendant(other: Sel): Sel = Sel.compound(rendered + " " + other.rendered)

  /** Child combinator (`>`): `cls("a").child(cls("b"))` → `.a > .b`. */
  def child(other: Sel): Sel = Sel.compound(rendered + " > " + other.rendered)

  /** Next-sibling combinator (`+`): `cls("a").nextSibling(cls("b"))` → `.a + .b`. */
  def nextSibling(other: Sel): Sel = Sel.compound(rendered + " + " + other.rendered)

  /** Subsequent-sibling combinator (`~`): `cls("a").subsequentSibling(cls("b"))` → `.a ~ .b`. */
  def subsequentSibling(other: Sel): Sel = Sel.compound(rendered + " ~ " + other.rendered)

  /** Column combinator (`||`): `col("c").column(tag("td"))` → `.c || td`. */
  def column(other: Sel): Sel = Sel.compound(rendered + " || " + other.rendered)

  /** Selector list (`,`) — match either: `cls("a").or(cls("b"))` → `.a, .b`. When used as a nested selector, the parent
    * path is prepended to EACH comma segment (see [[Selector]]).
    */
  def or(other: Sel): Sel = Sel.compound(rendered + ", " + other.rendered)
end Sel

object Sel:
  /** Internal concrete fragment. `ast` is populated ONLY by [[Sel.parse]] (never by the authoring DSL — `.cls`,
    * `.child`, etc. build compile-time-known selectors that have no need for runtime matching) and carries the
    * structural [[MatchNode]] that [[SelMatch]] evaluates. See [[Sel.matches]].
    */
  private[css] final case class Compound(rendered: String, ast: Option[MatchNode] = None) extends Sel

  private[css] def compound(s: String): Sel = Compound(s)

  /** Parse a CSS selector string into a [[Sel]] whose [[Compound.ast]] is populated — the ONLY way to obtain a `Sel`
    * that [[matches]] can evaluate against a [[Matchable]] element. Returns the parser's diagnostic message on a
    * malformed selector.
    */
  def parse(selector: String): Either[String, Sel] =
    SelectorGrammar.parse(selector).map(node => Compound(selector.trim, Some(node)))

  extension (s: Sel)
    /** Test whether `e` matches this selector — requires `s` to have been built via [[Sel.parse]] (its `ast` must be
      * populated); calling this on a hand-built `Sel` (e.g. `Cls("row")`, `PseudoClass.hover`) is a PROGRAMMER ERROR,
      * not a silent always-false — those selectors are compile-time-known authoring fragments with no structural AST to
      * evaluate, so misuse should fail loudly and immediately rather than mysteriously never matching.
      */
    def matches[E](e: E)(using m: Matchable[E]): Boolean =
      s match
        case Compound(_, Some(node)) => SelMatch.matches(node, e)
        case _                       =>
          throw IllegalArgumentException(
            "Sel.matches requires a parsed selector — call Sel.parse(...), not the authoring DSL (.cls/.child/...)."
          )
  end extension

  /** Escape hatch — wrap an arbitrary selector string the typed surface doesn't model. */
  def raw(selector: String): Sel = Compound(selector)

  /** A class selector: `cls("row")` → `.row`. */
  def cls(name: String): Sel = Compound("." + name)

  /** A class selector from a [[CssClass]] (reads its `.className`): `cls(Card)` → `.card-3f2a`. */
  def cls(c: CssClass): Sel = cls(c.className)

  /** An id selector: `id("main")` → `#main`. */
  def id(name: String): Sel = Compound("#" + name)

  /** A type (element) selector: `tag("li")` → `li`. */
  def tag(name: String): Sel = Compound(name)

  /** The universal selector `*`. */
  val universal: Sel = Compound("*")

  /** A presence attribute selector: `attr("required")` → `[required]`. */
  def attr(name: String): Sel = Compound(s"[$name]")

  /** A value attribute selector: `attr("type", AttrOp.Eq, "checkbox")` → `[type="checkbox"]`. */
  def attr(name: String, op: AttrOp, value: String): Sel = Compound(s"""[$name${op.render}"$value"]""")

  /** A bare pseudo-class fragment: `pseudoClass(PseudoClass.hover)` → `:hover`. (Or just use `PseudoClass.hover`.) */
  def pseudoClass(pc: PseudoClass): Sel = pc

  /** A bare pseudo-element fragment: `pseudoElement(PseudoElement.before)` → `::before`. */
  def pseudoElement(pe: PseudoElement): Sel = pe

  // relative combinators (parent-implicit) — for nested selectors that relate to the rule's ancestor path

  /** Relative descendant: renders ` <s>` (leading space) so a nested `Selector(descendant(tag("canvas")))(…)` becomes
    * `‹parent› canvas`.
    */
  def descendant(s: Sel): Sel = Compound(" " + s.rendered)

  /** Relative child: renders ` > <s>` — nested becomes `‹parent› > <s>`. */
  def child(s: Sel): Sel = Compound(" > " + s.rendered)

  /** Relative next-sibling: renders ` + <s>`. */
  def nextSibling(s: Sel): Sel = Compound(" + " + s.rendered)

  /** Relative subsequent-sibling: renders ` ~ <s>`. */
  def subsequentSibling(s: Sel): Sel = Compound(" ~ " + s.rendered)

  /** Relative column: renders ` || <s>`. */
  def column(s: Sel): Sel = Compound(" || " + s.rendered)
end Sel

/** Top-level class-selector constructor, exported under `ascent.*` so a fragment reads `Cls(Card)` / `Cls("row")`.
  * Capitalized to sit beside the value constructors (`Cls`, `Elem`, `Id`) in selector-composition position; the
  * lower-case fluent entry points live on [[Sel]] (`Sel.cls`) and [[Selector]] (`Selector.cls`).
  */
object Cls:
  def apply(name: String): Sel = Sel.cls(name)
  def apply(c: CssClass): Sel  = Sel.cls(c)

/** Top-level id-selector constructor: `Id("main")` → `#main`. */
object Id:
  def apply(name: String): Sel = Sel.id(name)

/** Top-level type/element-selector constructor: `Elem("li")` → `li`, plus the GENERATED typed element selectors
  * `Elem.button` / `Elem.div` / … (one per HTML element, from the same `elements/html.json` that drives `Elements`).
  * The `apply(String)` escape hatch covers custom elements / SVG / anything not in the catalog. Named `Elem` (not
  * `Tag`) to avoid clashing with `zio.Tag` in views that `import zio.*`.
  */
object Elem extends ElemsGenerated:
  def apply(name: String): Sel         = Sel.tag(name)
  protected def tag(name: String): Sel = Sel.tag(name)

/** The attribute-selector match operators (CSS Selectors level 4). */
enum AttrOp(val render: String):
  /** `[a="v"]` — exact match. */
  case Eq extends AttrOp("=")

  /** `[a~="v"]` — whitespace-separated word match. */
  case Includes extends AttrOp("~=")

  /** `[a|="v"]` — exact, or prefix immediately followed by `-` (language subtags). */
  case DashMatch extends AttrOp("|=")

  /** `[a^="v"]` — value starts with. */
  case Prefix extends AttrOp("^=")

  /** `[a$="v"]` — value ends with. */
  case Suffix extends AttrOp("$=")

  /** `[a*="v"]` — value contains. */
  case Substring extends AttrOp("*=")
end AttrOp

/** An `An+B` micro-syntax value for the `:nth-*()` pseudo-classes.
  *
  * `Nth(a, b)` renders the `<an+b>` form (`2n+1`, `3n`, `-n+3`, `5`); the named [[Nth.odd]] / [[Nth.even]] keywords and
  * the single-index [[Nth.apply(index:Int)*]] cover the common cases. `keyword` carries the spec's `odd`/`even`
  * spellings, which render verbatim instead of their `2n+1`/`2n` equivalents.
  */
final case class Nth(a: Int, b: Int, keyword: Option[String] = scala.None):
  /** The rendered `An+B` token, e.g. `2n+1`, `3n`, `-n+3`; `0n+5` collapses to `5`; `odd`/`even` render verbatim. */
  def render: String =
    keyword.getOrElse:
      if a == 0 then b.toString
      else
        val coeff = a match
          case 1  => "n"
          case -1 => "-n"
          case _  => s"${a}n"
        if b == 0 then coeff
        else if b > 0 then s"$coeff+$b"
        else s"$coeff-${-b}"
end Nth

object Nth:
  /** Every odd-indexed element (`odd` keyword == `2n+1`). */
  val odd: Nth = new Nth(2, 1, Some("odd"))

  /** Every even-indexed element (`even` keyword == `2n`). */
  val even: Nth = new Nth(2, 0, Some("even"))

  /** A single 1-based index: `Nth(3)` → `3` (the third element). */
  def apply(index: Int): Nth = new Nth(0, index, scala.None)
end Nth

/** A CSS pseudo-class (`:hover`, `:nth-child(2n+1)`, `:not(.x)`). [[render]] includes the leading colon.
  *
  * The simple (argument-less) pseudo-classes are GENERATED from the webref selectors catalog into
  * [[PseudoClassesGenerated]] (which this companion extends); the *functional* ones (`:nth-child()`, `:not()`, `:is()`,
  * `:where()`, `:has()`, `:dir()`, `:lang()`, `:state()`) are hand-written here because they take typed arguments.
  *
  * `PseudoClass extends Sel`, so `PseudoClass.hover` is directly usable anywhere a [[Sel]] is expected.
  */
final class PseudoClass private[css] (val render: String) extends Sel:
  private[css] def rendered: String = render

object PseudoClass extends PseudoClassesGenerated:
  /** A simple (argument-less) pseudo-class from its bare name (no leading colon): `simple("hover")` → `:hover`. The
    * generated catalog uses this; call it directly for a pseudo webref doesn't (yet) list.
    */
  def simple(name: String): PseudoClass = new PseudoClass(":" + name)

  /** Escape hatch — wrap a full pseudo-class string (including any leading colon + arguments). */
  def raw(rendered: String): PseudoClass = new PseudoClass(rendered)

  private def functional(name: String, arg: String): PseudoClass = new PseudoClass(s":$name($arg)")
  private def selectorList(sels: Seq[Sel]): String               = sels.map(_.rendered).mkString(", ")

  /** `:nth-child(<an+b>)` — `nthChild(Nth.odd)` / `nthChild(Nth(3))` / `nthChild(Nth(2, 1))`. */
  def nthChild(n: Nth): PseudoClass = functional("nth-child", n.render)

  /** `:nth-last-child(<an+b>)`. */
  def nthLastChild(n: Nth): PseudoClass = functional("nth-last-child", n.render)

  /** `:nth-of-type(<an+b>)`. */
  def nthOfType(n: Nth): PseudoClass = functional("nth-of-type", n.render)

  /** `:nth-last-of-type(<an+b>)`. */
  def nthLastOfType(n: Nth): PseudoClass = functional("nth-last-of-type", n.render)

  /** `:not(<selector-list>)` — negation: `not(cls("done"), cls("hidden"))` → `:not(.done, .hidden)`. */
  def not(sels: Sel*): PseudoClass = functional("not", selectorList(sels))

  /** `:is(<selector-list>)` — matches-any (forgiving). */
  def is(sels: Sel*): PseudoClass = functional("is", selectorList(sels))

  /** `:where(<selector-list>)` — like `:is()` but zero specificity. */
  def where(sels: Sel*): PseudoClass = functional("where", selectorList(sels))

  /** `:has(<selector-list>)` — relational: matches if it has a descendant matching the argument. */
  def has(sels: Sel*): PseudoClass = functional("has", selectorList(sels))

  /** `:dir(ltr | rtl)` — directionality. */
  def dir(direction: String): PseudoClass = functional("dir", direction)

  /** `:lang(<language>)` — language match: `lang("en")` / `lang("zh-Hans")`. */
  def lang(language: String): PseudoClass = functional("lang", language)

  /** `:state(<ident>)` — custom-element state (matches a `CustomStateSet` entry). */
  def state(ident: String): PseudoClass = functional("state", ident)
end PseudoClass

/** A CSS pseudo-element (`::before`, `::selection`, `::highlight(name)`). [[render]] includes the leading `::`.
  *
  * Simple pseudo-elements are GENERATED into [[PseudoElementsGenerated]]; the functional ones (`::highlight()`,
  * `::part()`, `::slotted()`) are hand-written. `PseudoElement extends Sel`.
  */
final class PseudoElement private[css] (val render: String) extends Sel:
  private[css] def rendered: String = render

object PseudoElement extends PseudoElementsGenerated:
  /** A simple pseudo-element from its bare name (no `::`): `simple("before")` → `::before`. */
  def simple(name: String): PseudoElement = new PseudoElement("::" + name)

  /** Escape hatch — wrap a full pseudo-element string (including `::` + any arguments). */
  def raw(rendered: String): PseudoElement = new PseudoElement(rendered)

  private def functional(name: String, arg: String): PseudoElement = new PseudoElement(s"::$name($arg)")

  /** `::highlight(<custom-ident>)` — a named custom highlight. */
  def highlight(name: String): PseudoElement = functional("highlight", name)

  /** `::part(<ident>+)` — a shadow-tree part exposed via `part=`. */
  def part(names: String*): PseudoElement = functional("part", names.mkString(" "))

  /** `::slotted(<compound-selector>)` — a slotted node matching the argument. */
  def slotted(sel: Sel): PseudoElement = functional("slotted", sel.rendered)
end PseudoElement
