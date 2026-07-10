package ascent.css

import zio.test.*

/** The [[Shadow]] value type — a typed `box-shadow` / `text-shadow` composed from [[Length]] offsets and a [[Color]],
  * with an optional spread and an `inset` flag, plus a comma-joined multi-shadow form ([[Shadow.list]]). One type
  * covers both grammars, since a `text-shadow` is just a shadow with no spread and no inset.
  */
object ShadowSpec extends ZIOSpecDefault:

  def spec = suite("Shadow")(
    test("a basic shadow: offsets + blur + color (a text-shadow / soft box-shadow)") {
      val s = Shadow(Length.zero, Length.px(24), Length.px(80), Color.rgba(0, 0, 0, 0.45))
      assertTrue(s.render == "0 24.0px 80.0px rgb(0 0 0 / 0.45)")
    },
    test("a shadow with spread renders offsetX offsetY blur spread color") {
      val s = Shadow(Length.zero, Length.zero, Length.zero, Length.px(3), Color.hex("#ff00aa").alpha(0.35))
      assertTrue(s.render == "0 0 0 3.0px rgb(255 0 170 / 0.35)")
    },
    test("an inset shadow leads with `inset`") {
      val s = Shadow.inset(Length.zero, Length.px(1), Length.zero, Color.rgba(255, 255, 255, 0.04))
      assertTrue(s.render == "inset 0 1.0px 0 rgb(255 255 255 / 0.04)")
    },
    test("an inset shadow with spread (the drop-target marker)") {
      val s = Shadow.inset(Length.zero, Length.px(3), Length.zero, Length.zero, Color.hex("#ff00aa"))
      assertTrue(s.render == "inset 0 3.0px 0 0 rgb(255, 0, 170)")
    },
    test("Shadow.list comma-joins multiple shadows (the glass card stack)") {
      val accent = Color.hex("#ff00aa")
      val s      = Shadow.list(
        Shadow(Length.zero, Length.px(24), Length.px(80), Color.rgba(0, 0, 0, 0.45)),
        Shadow(Length.zero, Length.px(4), Length.px(12), accent.alpha(0.12)),
        Shadow.inset(Length.zero, Length.px(1), Length.zero, Color.rgba(255, 255, 255, 0.04)),
      )
      assertTrue(
        s.render == "0 24.0px 80.0px rgb(0 0 0 / 0.45), 0 4.0px 12.0px rgb(255 0 170 / 0.12), " +
          "inset 0 1.0px 0 rgb(255 255 255 / 0.04)"
      )
    },
    test("a Shadow attaches to box-shadow / text-shadow via the universal overload") {
      val s = Shadow(Length.zero, Length.zero, Length.px(12), Color.hex("#ff00aa").alpha(0.35))
      assertTrue(Styles.boxShadow(s).render == "box-shadow: 0 0 12.0px rgb(255 0 170 / 0.35);")
    },
  )
end ShadowSpec
