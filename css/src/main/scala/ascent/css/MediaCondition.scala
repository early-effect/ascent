package ascent.css

import StylesFoundation.formatDouble

/** A typed `@media` query condition. Renders to the parenthesised CSS source the W3C `@media` grammar accepts, and
  * composes via `.and` / `.or` / `.not`.
  *
  * The lowest leaves are produced by the GENERATED [[MediaFeatures]] trait: each spec-listed media feature (from
  * webref's `atrules[].descriptors[]` for `@media`) becomes a typed `Feature`-shaped value with named keyword constants
  * and/or typed builders. Composition happens at this level — [[Combined]] joins two conditions with `and` / `,` (CSS
  * spells disjunction with a comma) and [[Not]] negates one.
  *
  * Why a value type instead of just a `String`: the typed surface lets the call site mis-type-check (e.g.
  * `Media.maxWidth.px(600)` is a length, not an integer) AND it lets `MediaQuery` accept either a typed condition or a
  * String escape hatch via a single apply-overload, so generators downstream of @media (`@container`, `@supports`) can
  * reuse the same composition surface.
  */
sealed trait MediaCondition:
  /** The CSS source for this condition (parens included for atomic features, `and`/`,` separators for composed ones,
    * `not <inner>` for negation). Plug this directly into the `@media` rule's query slot.
    */
  def render: String

  /** Conjunction: `(A) and (B)`. CSS @media level 3 syntax. */
  infix def and(other: MediaCondition): MediaCondition = MediaCondition.Combined(this, " and ", other)

  /** Disjunction: `A, B`. CSS @media uses comma-separation for "or". */
  infix def or(other: MediaCondition): MediaCondition = MediaCondition.Combined(this, ", ", other)

  /** Negation: `not <inner>`. */
  def not: MediaCondition = MediaCondition.Not(this)
end MediaCondition

object MediaCondition:

  /** A bare keyword feature: `(orientation: portrait)`. */
  final case class Keyword(name: String, value: String) extends MediaCondition:
    def render: String = s"($name: $value)"

  /** A typed-value feature with a pre-rendered value string: `(max-width: 600px)`. */
  final case class Valued(name: String, value: String) extends MediaCondition:
    def render: String = s"($name: $value)"

  /** Two conditions joined by a separator (` and ` for conjunction, `, ` for disjunction). */
  final case class Combined(left: MediaCondition, sep: String, right: MediaCondition) extends MediaCondition:
    def render: String = left.render + sep + right.render

  /** Negation: prepends `not ` to the inner condition's rendered form. */
  final case class Not(inner: MediaCondition) extends MediaCondition:
    def render: String = "not " + inner.render

  /** A condition produced from a hand-rolled spec snippet — the escape hatch for grammar shapes the typed feature
    * surface doesn't model yet (range syntax, custom features, vendor prefixes). The string is used as-is — the author
    * is responsible for the parens.
    */
  final case class Raw(query: String) extends MediaCondition:
    def render: String = query
end MediaCondition

/** Foundation: the `MF` (media-feature) base class and the value-grammar traits each GENERATED feature object mixes in.
  * Lives at the package level so [[MediaFeatures]] (generated) and any hand-written overrides can both extend `MF`
  * without going through the [[Media]] entry point.
  *
  * The traits mirror [[StylesFoundation]]'s shape (Length / Numeric / etc.) but produce [[MediaCondition]] values
  * instead of [[Declaration]]s — the rendered grammar is the same shape (a unit-suffixed number, a keyword), only the
  * wrapper differs.
  */
object MediaFoundation:

  /** A typed @media feature constructor. The unparameterized `apply(String)` is always available as an escape hatch for
    * raw values that the typed mixins don't model.
    */
  abstract class MF(val feature: String):
    def apply(value: String): MediaCondition = MediaCondition.Valued(feature, value)

  /** Mixin: `<feature>: <n><length-unit>`. Mirrors [[StylesFoundation.Length]]. */
  trait Length extends MF:
    private def suffixed(s: String)(n: Double): MediaCondition =
      MediaCondition.Valued(feature, s"${formatDouble(n)}$s")
    val px: Double => MediaCondition   = suffixed("px")
    val em: Double => MediaCondition   = suffixed("em")
    val rem: Double => MediaCondition  = suffixed("rem")
    val pt: Double => MediaCondition   = suffixed("pt")
    val pc: Double => MediaCondition   = suffixed("pc")
    val cm: Double => MediaCondition   = suffixed("cm")
    val mm: Double => MediaCondition   = suffixed("mm")
    val in: Double => MediaCondition   = suffixed("in")
    val q: Double => MediaCondition    = suffixed("Q")
    val ex: Double => MediaCondition   = suffixed("ex")
    val ch: Double => MediaCondition   = suffixed("ch")
    val vh: Double => MediaCondition   = suffixed("vh")
    val vw: Double => MediaCondition   = suffixed("vw")
    val vmin: Double => MediaCondition = suffixed("vmin")
    val vmax: Double => MediaCondition = suffixed("vmax")
  end Length

  /** Mixin: numeric value (Int) — for descriptors typed as `<integer>`. */
  trait Numeric extends MF:
    def apply(n: Int): MediaCondition = MediaCondition.Valued(feature, n.toString)

  /** Mixin: `<feature>: <n>dpi | <n>dppx | <n>dpcm`. The CSS `<resolution>` type. */
  trait Resolution extends MF:
    private def suffixed(s: String)(n: Double): MediaCondition =
      MediaCondition.Valued(feature, s"${formatDouble(n)}$s")
    val dpi: Double => MediaCondition  = suffixed("dpi")
    val dppx: Double => MediaCondition = suffixed("dppx")
    val dpcm: Double => MediaCondition = suffixed("dpcm")

  /** Mixin: `<feature>: <a>/<b>`. The CSS `<ratio>` type — call as `feature.apply(16, 9)`. */
  trait Ratio extends MF:
    def apply(a: Int, b: Int): MediaCondition =
      MediaCondition.Valued(feature, s"$a/$b")
end MediaFoundation
