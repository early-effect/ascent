package ascent.ast

import zio.test.*

object ServerRegionSpec extends ZIOSpecDefault:

  def spec = suite("UI.ServerRegion")(
    test("carries its id and defaults tag to div, is UI[Any]") {
      val r: UI[Any] = UI.ServerRegion("cart")
      assertTrue(r == UI.ServerRegion("cart", "div"))
    },
    test("two regions with different ids get different structural ids") {
      assertTrue(AstId.compute(UI.ServerRegion("a")) != AstId.compute(UI.ServerRegion("b")))
    },
    test("same id+tag is stable (idempotent hash)") {
      assertTrue(AstId.compute(UI.ServerRegion("a")) == AstId.compute(UI.ServerRegion("a")))
    },
    test("a region's id differs from a same-named element's id (distinct node kind)") {
      val region  = UI.ServerRegion("x", "div")
      val element = UI.Element[Any]("div", Vector.empty, Vector.empty)
      assertTrue(AstId.compute(region) != AstId.compute(element))
    },
    test("tag participates in identity") {
      assertTrue(AstId.compute(UI.ServerRegion("x", "section")) != AstId.compute(UI.ServerRegion("x", "div")))
    },
  )
end ServerRegionSpec
