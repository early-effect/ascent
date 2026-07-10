package ascent.css

import StylesFoundation.formatDouble

/** A typed `@container` query condition. Parallel to [[MediaCondition]]: same composition shape (`.and` / `.or` /
  * `.not`) and the same parenthesized rendering, but a DISTINCT type so a Container condition can't be passed to
  * `MediaQuery` and vice-versa. The descriptor sets are different (`@container` adds `inline-size` / `block-size` /
  * `aspect-ratio` / `orientation`; `@media` has `prefers-reduced-motion`, `pointer`, etc.) and the at-rule lookup
  * grammar is different — keeping the types separate prevents cross-mixing at the call site.
  */
sealed trait ContainerCondition:
  def render: String
  infix def and(other: ContainerCondition): ContainerCondition =
    ContainerCondition.Combined(this, " and ", other)
  infix def or(other: ContainerCondition): ContainerCondition =
    ContainerCondition.Combined(this, ", ", other)
  def not: ContainerCondition = ContainerCondition.Not(this)
end ContainerCondition

object ContainerCondition:
  final case class Keyword(name: String, value: String) extends ContainerCondition:
    def render: String = s"($name: $value)"
  final case class Valued(name: String, value: String) extends ContainerCondition:
    def render: String = s"($name: $value)"
  final case class Combined(left: ContainerCondition, sep: String, right: ContainerCondition)
      extends ContainerCondition:
    def render: String = left.render + sep + right.render
  final case class Not(inner: ContainerCondition) extends ContainerCondition:
    def render: String = "not " + inner.render
  final case class Raw(query: String) extends ContainerCondition:
    def render: String = query
end ContainerCondition

/** Foundation: the `CF` (container-feature) base class and value-grammar traits each GENERATED feature object mixes in.
  * Parallel to [[MediaFoundation]] — same trait set, but produces [[ContainerCondition]] values instead of
  * [[MediaCondition]] values.
  */
object ContainerFoundation:

  abstract class CF(val feature: String):
    def apply(value: String): ContainerCondition = ContainerCondition.Valued(feature, value)

  trait Length extends CF:
    private def suffixed(s: String)(n: Double): ContainerCondition =
      ContainerCondition.Valued(feature, s"${formatDouble(n)}$s")
    val px: Double => ContainerCondition   = suffixed("px")
    val em: Double => ContainerCondition   = suffixed("em")
    val rem: Double => ContainerCondition  = suffixed("rem")
    val pt: Double => ContainerCondition   = suffixed("pt")
    val pc: Double => ContainerCondition   = suffixed("pc")
    val cm: Double => ContainerCondition   = suffixed("cm")
    val mm: Double => ContainerCondition   = suffixed("mm")
    val in: Double => ContainerCondition   = suffixed("in")
    val q: Double => ContainerCondition    = suffixed("Q")
    val ex: Double => ContainerCondition   = suffixed("ex")
    val ch: Double => ContainerCondition   = suffixed("ch")
    val vh: Double => ContainerCondition   = suffixed("vh")
    val vw: Double => ContainerCondition   = suffixed("vw")
    val vmin: Double => ContainerCondition = suffixed("vmin")
    val vmax: Double => ContainerCondition = suffixed("vmax")
  end Length

  trait Numeric extends CF:
    def apply(n: Int): ContainerCondition = ContainerCondition.Valued(feature, n.toString)

  trait Resolution extends CF:
    private def suffixed(s: String)(n: Double): ContainerCondition =
      ContainerCondition.Valued(feature, s"${formatDouble(n)}$s")
    val dpi: Double => ContainerCondition  = suffixed("dpi")
    val dppx: Double => ContainerCondition = suffixed("dppx")
    val dpcm: Double => ContainerCondition = suffixed("dpcm")

  trait Ratio extends CF:
    def apply(a: Int, b: Int): ContainerCondition =
      ContainerCondition.Valued(feature, s"$a/$b")
end ContainerFoundation
