package ascent.css

import StylesFoundation.formatDouble

/** A composable CSS grid track size or track list — the value of `grid-template-columns` / `grid-template-rows` /
  * `grid-auto-columns` / `grid-auto-rows`.
  *
  * A single track is a [[Length]] (`100px`, `20%`), a flex `<flex>` (`1fr`), `auto`/`min-content`/`max-content`, or a
  * `minmax(...)` / `fit-content(...)` function. [[GridTrack.list]] space-joins tracks into a track list, and
  * [[GridTrack.repeat]] expands `repeat(n, …)`. The named-line / `[line-name]` form and the full
  * `<grid-template-areas>` string stay on the `apply(String)` escape hatch (open-ended). Attaches via the universal
  * `apply(CssValue)` overload.
  */
sealed trait GridTrack extends CssValue:
  override def toString: String = render

object GridTrack:

  /** A pre-rendered single track (a length, `1fr`, a keyword, or a function). */
  final case class Track(rendered: String) extends GridTrack:
    def render: String = rendered

  /** A space-joined track list — what a `grid-template-columns` value usually is. */
  final case class TrackList(tracks: List[GridTrack]) extends GridTrack:
    def render: String = tracks.map(_.render).mkString(" ")

  /** A fixed length/percentage track. */
  def of(length: Length): GridTrack = Track(length.render)

  /** A fractional (`<flex>`) track: `fr(1)` → `1fr`. */
  def fr(n: Double): GridTrack = Track(s"${formatDouble(n)}fr")

  val auto: GridTrack       = Track("auto")
  val minContent: GridTrack = Track("min-content")
  val maxContent: GridTrack = Track("max-content")

  /** `minmax(min, max)` — both arguments are pre-rendered tracks (a length, `1fr`, a keyword). */
  def minmax(min: GridTrack, max: GridTrack): GridTrack = Track(s"minmax(${min.render}, ${max.render})")

  /** `fit-content(<length-percentage>)`. */
  def fitContent(limit: Length): GridTrack = Track(s"fit-content(${limit.render})")

  /** `repeat(count, <tracks>)` — `count` is an integer; the tracks are space-joined. */
  def repeat(count: Int, tracks: GridTrack*): GridTrack =
    Track(s"repeat($count, ${tracks.map(_.render).mkString(" ")})")

  /** Space-join tracks into a track list: `GridTrack.list(GridTrack.fr(1), GridTrack.of(Length.px(200)))`. */
  def list(tracks: GridTrack*): GridTrack = TrackList(tracks.toList)
end GridTrack
