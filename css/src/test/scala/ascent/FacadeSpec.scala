package ascent

// Deliberately NO `import ascent.css.*` — this proves the single `import ascent.*` wildcard alone exposes the surface.
import ascent.*
import zio.test.*

/** Compile-anchored proof that `import ascent.*` exposes the css authoring types + the `S` alias for the `Styles`
  * property catalog (whose members are reached via `S.color(...)`, not enumerated in the facade).
  */
object FacadeSpec extends ZIOSpecDefault:

  val red: Declaration = S.color("red")

  object Box extends CssClass(S.display("flex"), S.padding.px(8))

  val sel: Selector = Selector(":hover", S.color("blue"))

  def spec = suite("ascent.* facade (css)")(
    test("S is the same object as Styles (alias does not drift)") {
      assertTrue((S: AnyRef) eq (Styles: AnyRef))
    },
    test("authoring types resolve through the facade and build the expected declarations") {
      assertTrue(
        red == Declaration("color", "red"),
        Box.renderCss.contains("display: flex;"),
        sel.selector == ":hover",
      )
    },
    test("Tooltip (css utility) is reachable through the facade") {
      assertTrue(Tooltip("hi").nonEmpty)
    },
  )
end FacadeSpec
