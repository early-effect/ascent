package ascent.css

import zio.test.*

/** The [[GridTrack]] value type — track sizes + `minmax`/`repeat`/`fit-content` track lists for the grid-template /
  * grid-auto properties (formerly bare).
  */
object GridTrackSpec extends ZIOSpecDefault:

  def spec = suite("GridTrack")(
    test("fr renders a fractional track (platform-stable doubles, like Length)") {
      assertTrue(GridTrack.fr(1).render == "1.0fr", GridTrack.fr(2.5).render == "2.5fr")
    },
    test("a length track + keyword tracks") {
      assertTrue(
        GridTrack.of(Length.px(200)).render == "200.0px",
        GridTrack.auto.render == "auto",
        GridTrack.minContent.render == "min-content",
      )
    },
    test("minmax + fit-content + repeat") {
      assertTrue(
        GridTrack.minmax(GridTrack.of(Length.px(100)), GridTrack.fr(1)).render == "minmax(100.0px, 1.0fr)",
        GridTrack.fitContent(Length.px(300)).render == "fit-content(300.0px)",
        GridTrack.repeat(3, GridTrack.fr(1)).render == "repeat(3, 1.0fr)",
      )
    },
    test("list space-joins tracks") {
      assertTrue(
        GridTrack.list(GridTrack.fr(1), GridTrack.of(Length.px(200)), GridTrack.auto).render == "1.0fr 200.0px auto"
      )
    },
    test("attaches to grid-template-columns / grid-auto-columns via GridTrackish") {
      import Styles.*
      assertTrue(
        gridTemplateColumns.list(GridTrack.repeat(2, GridTrack.fr(1)), GridTrack.auto).render
          == "grid-template-columns: repeat(2, 1.0fr) auto;",
        gridAutoColumns(GridTrack.minContent).render == "grid-auto-columns: min-content;",
      )
    },
  )
end GridTrackSpec
