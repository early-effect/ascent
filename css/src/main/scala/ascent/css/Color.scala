package ascent.css

import StylesFoundation.formatDouble

/** A typed, composable CSS color. Mirrors [[MediaCondition]]'s sealed shape: a sealed trait with a `render` contract,
  * concrete cases ([[Color.Rgba]], [[Color.Hsla]], [[Color.Keyword]]), derivation methods on the trait, and a companion
  * of constructors.
  *
  * Why rich rather than an opaque string: design systems derive shades from a base — `accent.alpha(0.65)`,
  * `accent.lighten(0.1)` — and a string can't be transformed. Hex tokens normalize to [[Color.Rgba]] so derivation is
  * exact; see [[Color.hex]].
  *
  * Rendering follows CSS Color 4: opaque colors render the legacy comma form (`rgb(255, 0, 170)`) because it reads
  * cleanest, and any sub-1 alpha renders the modern space + slash-alpha form (`rgb(255 0 170 / 0.65)`).
  */
sealed trait Color extends CssValue:

  /** This color at the given alpha (clamped to 0..1). On a [[Color.Keyword]] (named / system / `currentColor` /
    * `transparent`) — which carries no channels — alpha returns `self` unchanged; see the type's note.
    */
  def alpha(a: Double): Color

  /** Lighten by `amount` (0..1) in HSL lightness space — `amount` is an absolute lightness delta scaled to percent, so
    * `lighten(0.1)` adds 10 percentage points of lightness (clamped to 100). Keyword colors return `self`.
    */
  def lighten(amount: Double): Color

  /** Darken by `amount` (0..1) in HSL lightness space (the inverse of [[lighten]]). Keyword colors return `self`. */
  def darken(amount: Double): Color

  /** Linear blend toward `other` by `weight` (0..1, clamped) in sRGB space — `0` is `self`, `1` is `other`. No gamma
    * correction in v1. If either side carries no channels (a keyword color), returns `self`.
    */
  def mix(other: Color, weight: Double): Color

  /** So string interpolation (`s"1px solid $color"`) emits valid CSS during the incremental migration of stringly-typed
    * declarations to typed values.
    */
  override def toString: String = render

  /** The sRGB channels backing this color, if it has any. `None` for keyword colors in v1. */
  private[css] def channels: Option[Color.Rgba]
end Color

object Color:

  /** sRGB with alpha. Opaque (`a >= 1`) renders `rgb(r, g, b)`; otherwise the modern `rgb(r g b / a)` form. */
  final case class Rgba(r: Int, g: Int, b: Int, a: Double) extends Color:
    def render: String =
      if a >= 1.0 then s"rgb($r, $g, $b)"
      else s"rgb($r $g $b / ${renderAlpha(a)})"
    def alpha(x: Double): Color             = copy(a = clampAlpha(x))
    def lighten(amount: Double): Color      = toHsla.lighten(amount)
    def darken(amount: Double): Color       = toHsla.darken(amount)
    def mix(other: Color, w: Double): Color =
      other.channels.fold[Color](this)(o => mixRgb(this, o, clampAlpha(w)))
    private[css] def channels: Option[Rgba] = Some(this)
    private[css] def toHsla: Hsla           = rgbToHsla(this)
  end Rgba

  /** HSL with alpha. Opaque renders `hsl(h, s%, l%)`; sub-1 alpha renders `hsl(h s% l% / a)`. */
  final case class Hsla(h: Int, s: Double, l: Double, a: Double) extends Color:
    def render: String =
      if a >= 1.0 then s"hsl($h, ${formatDouble(s)}%, ${formatDouble(l)}%)"
      else s"hsl($h ${formatDouble(s)}% ${formatDouble(l)}% / ${renderAlpha(a)})"
    def alpha(x: Double): Color             = copy(a = clampAlpha(x))
    def lighten(amount: Double): Color      = copy(l = clampPct(l + amount * 100))
    def darken(amount: Double): Color       = copy(l = clampPct(l - amount * 100))
    def mix(other: Color, w: Double): Color = toRgba.mix(other, w)
    private[css] def channels: Option[Rgba] = Some(toRgba)
    private[css] def toRgba: Rgba           = hslaToRgba(this)
  end Hsla

  /** A named color, system color, `currentColor`, or `transparent` — kept verbatim.
    *
    * v1 caveat: a keyword carries no channels, so [[alpha]] / [[lighten]] / [[darken]] / [[mix]] return `self`. The
    * named-color resolution table is a later addition; TodoStyles derives only from hex, so this never bites.
    */
  final case class Keyword(name: String) extends Color:
    def render: String                      = name
    def alpha(x: Double): Color             = this
    def lighten(amount: Double): Color      = this
    def darken(amount: Double): Color       = this
    def mix(other: Color, w: Double): Color = this
    private[css] def channels: Option[Rgba] = scala.None
  end Keyword

  def rgb(r: Int, g: Int, b: Int): Color             = Rgba(clampByte(r), clampByte(g), clampByte(b), 1.0)
  def rgba(r: Int, g: Int, b: Int, a: Double): Color = Rgba(clampByte(r), clampByte(g), clampByte(b), clampAlpha(a))
  def hsl(h: Int, s: Double, l: Double): Color       = Hsla(h, clampPct(s), clampPct(l), 1.0)
  def hsla(h: Int, s: Double, l: Double, a: Double): Color = Hsla(h, clampPct(s), clampPct(l), clampAlpha(a))

  /** A literal CSS color keyword: a named color, a system color, `currentColor`, or `transparent`. */
  def keyword(name: String): Color = Keyword(name)
  val transparent: Color           = Keyword("transparent")
  val currentColor: Color          = Keyword("currentColor")

  /** Parse a hex color into a normalized [[Rgba]]. Accepts `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa` (leading `#`
    * optional). Hex tokens are compile-time-authored constants, so malformed input is a programmer error: this throws
    * `IllegalArgumentException` rather than silently rendering garbage.
    */
  def hex(s: String): Color =
    val h                    = (if s.startsWith("#") then s.substring(1) else s).toLowerCase
    def nibble(c: Char): Int =
      val v = Character.digit(c, 16)
      if v < 0 then throw IllegalArgumentException(s"Invalid hex color: '$s'")
      v
    def byte(i: Int): Int     = nibble(h.charAt(i)) * 16 + nibble(h.charAt(i + 1))
    def dbl(i: Int): Int      = nibble(h.charAt(i)) * 17 // expand a single nibble: 0xF -> 0xFF
    def aByte(n: Int): Double = round3(n / 255.0)
    h.length match
      case 3 => Rgba(dbl(0), dbl(1), dbl(2), 1.0)
      case 4 => Rgba(dbl(0), dbl(1), dbl(2), aByte(nibble(h.charAt(3)) * 17))
      case 6 => Rgba(byte(0), byte(2), byte(4), 1.0)
      case 8 => Rgba(byte(0), byte(2), byte(4), aByte(byte(6)))
      case _ => throw IllegalArgumentException(s"Invalid hex color: '$s'")
    end match
  end hex

  private[css] def clampByte(n: Int): Int        = math.max(0, math.min(255, n))
  private[css] def clampAlpha(a: Double): Double = math.max(0.0, math.min(1.0, a))
  private[css] def clampPct(p: Double): Double   = math.max(0.0, math.min(100.0, p))

  /** Round to 3 decimal places — keeps derived alphas (e.g. `128/255`) platform-stable and short. */
  private def round3(d: Double): Double = math.round(d * 1000.0) / 1000.0

  /** Render an alpha channel: rounded to 3dp then formatted platform-stably. */
  private def renderAlpha(a: Double): String = formatDouble(round3(a))

  /** Per-channel sRGB linear blend; alpha blended likewise. */
  private def mixRgb(x: Rgba, y: Rgba, w: Double): Color =
    def lerp(p: Int, q: Int): Int = math.round(p * (1 - w) + q * w).toInt
    Rgba(lerp(x.r, y.r), lerp(x.g, y.g), lerp(x.b, y.b), clampAlpha(x.a * (1 - w) + y.a * w))

  /** sRGB → HSL. Hue rounds to a whole degree; saturation/lightness round to 1dp (percent), for stable, short output.
    */
  private def rgbToHsla(c: Rgba): Hsla =
    val rf    = c.r / 255.0
    val gf    = c.g / 255.0
    val bf    = c.b / 255.0
    val max   = math.max(rf, math.max(gf, bf))
    val min   = math.min(rf, math.min(gf, bf))
    val delta = max - min
    val l     = (max + min) / 2.0
    val s     = if delta == 0.0 then 0.0 else delta / (1.0 - math.abs(2.0 * l - 1.0))
    val hRaw  =
      if delta == 0.0 then 0.0
      else if max == rf then ((gf - bf) / delta) % 6.0
      else if max == gf then (bf - rf) / delta + 2.0
      else (rf - gf) / delta + 4.0
    val hDeg =
      val d = hRaw * 60.0
      if d < 0 then d + 360.0 else d
    Hsla(math.round(hDeg).toInt % 360, round1(s * 100.0), round1(l * 100.0), c.a)
  end rgbToHsla

  /** HSL → sRGB (channels round to nearest byte). */
  private def hslaToRgba(c: Hsla): Rgba =
    val s                       = c.s / 100.0
    val l                       = c.l / 100.0
    val k                       = (n: Double) => (n + c.h / 30.0) % 12.0
    val a                       = s * math.min(l, 1.0 - l)
    def channel(n: Double): Int =
      val kn = k(n)
      val f  = l - a * math.max(-1.0, math.min(math.min(kn - 3.0, 9.0 - kn), 1.0))
      math.round(f * 255.0).toInt
    Rgba(channel(0.0), channel(8.0), channel(4.0), c.a)
  end hslaToRgba

  private def round1(d: Double): Double = math.round(d * 10.0) / 10.0
end Color
