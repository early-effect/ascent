package ascent

import ascent.*
import ascent.dsl.*
import zio.test.*

/** Compile-anchored proof that the exact two-line view import (`import ascent.*` + `import ascent.dsl.*`) exposes the
  * js public surface (Mount, Canvas, TypedEvents) and the `Ev` alias, composing across the dom-types + core + js jars.
  */
object FacadeSpec extends ZIOSpecDefault:

  val viaAlias: Attr[Any] = Ev.onClick(_ => zio.ZIO.unit)
  val viaFull: Attr[Any]  = TypedEvents.onClick(_ => zio.ZIO.unit)

  // The ergonomic EventKey DSL (dsl.*) over the generated Events catalog (ascent.*).
  val viaEventKey: Attr[Any] = Events.onInput(_ => zio.ZIO.unit)

  def spec = suite("ascent.* facade (js)")(
    test("Ev is the same object as TypedEvents (alias does not drift)") {
      assertTrue((Ev: AnyRef) eq (TypedEvents: AnyRef))
    },
    test("AscentApp and Canvas resolve through the facade") {
      assertTrue((AscentApp: AnyRef) ne null, (Canvas: AnyRef) ne null)
    },
    test("typed-DOM factory and the EventKey DSL both build EventHandler attrs") {
      assertTrue(
        viaAlias.isInstanceOf[Attr.EventHandler[?]],
        viaFull.isInstanceOf[Attr.EventHandler[?]],
        viaEventKey == Attr.EventHandler("input", viaEventKey.asInstanceOf[Attr.EventHandler[Any]].handler),
      )
    },
  )
end FacadeSpec
