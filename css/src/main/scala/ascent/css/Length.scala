package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS `<length-percentage>` VALUE — `8px`, `1.5rem`, `50%`, `0`.
  *
  * Distinct from the `StylesFoundation.Length` *trait* that backs `fontSize.px(24)`: that trait produces a
  * [[Declaration]] directly and only exists to give property objects their unit methods. This is a first-class value
  * you store in a `val` and feed to composites — a [[Shadow]] offset, a gradient [[ColorStop]] position, a filter
  * radius — where a property method can't reach. Attaches to any property via the universal `apply(CssValue)` overload.
  *
  * `pct` lives here (not on a separate `Percent` value type) because CSS treats `<length-percentage>` as one grammar
  * and the composites that consume lengths accept either.
  */
sealed trait Length extends CssValue:
  override def toString: String = render

object Length:

  /** A number + unit suffix (`8` + `px`). */
  final case class Unit(n: Double, suffix: String) extends Length:
    def render: String = s"${formatDouble(n)}$suffix"

  /** The unitless `0` — the only length CSS accepts without a unit. */
  case object Zero extends Length:
    def render: String = "0"

  /** A bare keyword that's valid where a length-or-keyword is expected in a shorthand — `auto` (per-side on `margin`),
    * `min-content`/`max-content`/`fit-content` (grid track sizes), etc. Lets a typed shorthand mix lengths + keywords
    * without a `String` overload: `margin(0, Length.auto)`.
    */
  final case class Keyword(name: String) extends Length:
    def render: String = name

  val px: Double => Length   = Unit(_, "px")
  val em: Double => Length   = Unit(_, "em")
  val rem: Double => Length  = Unit(_, "rem")
  val pt: Double => Length   = Unit(_, "pt")
  val pc: Double => Length   = Unit(_, "pc")
  val cm: Double => Length   = Unit(_, "cm")
  val mm: Double => Length   = Unit(_, "mm")
  val q: Double => Length    = Unit(_, "Q")
  val ex: Double => Length   = Unit(_, "ex")
  val ch: Double => Length   = Unit(_, "ch")
  val vh: Double => Length   = Unit(_, "vh")
  val vw: Double => Length   = Unit(_, "vw")
  val vmin: Double => Length = Unit(_, "vmin")
  val vmax: Double => Length = Unit(_, "vmax")

  /** `in` (inches) — a method (not a `val => Length` like the others) because `in` is a Scala soft keyword that can't
    * be an extension-method name; call `Length.in(0.75)`.
    */
  def in(n: Double): Length = Unit(n, "in")

  /** The percentage half of `<length-percentage>`. */
  def pct(n: Double): Length = Unit(n, "%")

  /** The unitless `0`. */
  val zero: Length = Zero

  /** `auto` — valid per-side in `margin`/`inset` shorthands (and wherever a length-or-`auto` is expected). */
  val auto: Length = Keyword("auto")

  /** The bare literal `0` IS a valid length (the only unitless one CSS accepts), so `margin(0, 14.px, …)` reads
    * naturally. Restricted to the SINGLETON literal `0` — a non-zero bare `Int` (`5`) is a compile error, since `5`
    * without a unit is invalid CSS. (Use `5.px` / `5.pct` for those.)
    */
  given zeroToLength: Conversion[0, Length] = _ => Zero
end Length

/** Numeric → [[Length]] extension methods — the ergonomic authoring surface: `14.px`, `1.5.rem`, `50.pct`, `100.vh`.
  *
  * Top-level in `package ascent.css`, so `import ascent.css.*` brings them in directly; the `package ascent` facade
  * also re-exports them so a plain `import ascent.*` works. Defined on both `Int` and `Double`. Each builds the
  * matching [[Length.Unit]]; a whole number renders with the platform-stable trailing `.0` (`14.px` → `14.0px`),
  * matching `Length.px(14)`. (`in` is omitted — it's a Scala soft keyword; use `Length.in(n)`.)
  */
extension (n: Double)
  def px: Length   = Length.Unit(n, "px")
  def em: Length   = Length.Unit(n, "em")
  def rem: Length  = Length.Unit(n, "rem")
  def pt: Length   = Length.Unit(n, "pt")
  def pc: Length   = Length.Unit(n, "pc")
  def cm: Length   = Length.Unit(n, "cm")
  def mm: Length   = Length.Unit(n, "mm")
  def q: Length    = Length.Unit(n, "Q")
  def ex: Length   = Length.Unit(n, "ex")
  def ch: Length   = Length.Unit(n, "ch")
  def vh: Length   = Length.Unit(n, "vh")
  def vw: Length   = Length.Unit(n, "vw")
  def vmin: Length = Length.Unit(n, "vmin")
  def vmax: Length = Length.Unit(n, "vmax")
  def pct: Length  = Length.Unit(n, "%")
end extension

extension (n: Int)
  def px: Length   = Length.Unit(n.toDouble, "px")
  def em: Length   = Length.Unit(n.toDouble, "em")
  def rem: Length  = Length.Unit(n.toDouble, "rem")
  def pt: Length   = Length.Unit(n.toDouble, "pt")
  def pc: Length   = Length.Unit(n.toDouble, "pc")
  def cm: Length   = Length.Unit(n.toDouble, "cm")
  def mm: Length   = Length.Unit(n.toDouble, "mm")
  def q: Length    = Length.Unit(n.toDouble, "Q")
  def ex: Length   = Length.Unit(n.toDouble, "ex")
  def ch: Length   = Length.Unit(n.toDouble, "ch")
  def vh: Length   = Length.Unit(n.toDouble, "vh")
  def vw: Length   = Length.Unit(n.toDouble, "vw")
  def vmin: Length = Length.Unit(n.toDouble, "vmin")
  def vmax: Length = Length.Unit(n.toDouble, "vmax")
  def pct: Length  = Length.Unit(n.toDouble, "%")
end extension
