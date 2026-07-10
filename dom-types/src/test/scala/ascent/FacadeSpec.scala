package ascent

import ascent.*
import zio.test.*

/** Compile-anchored proof that a single `import ascent.*` (no `import ascent.domtypes.*`) resolves the dom-types
  * catalog and its short aliases; if any name below fails to resolve, the export facade is incomplete.
  */
object FacadeSpec extends ZIOSpecDefault:

  val divK: ElementKey        = Elements.div
  val classK: AttrKey[String] = Attrs.className
  val roleK: AttrKey[String]  = AriaAttrs.role
  val onClickK: EventKey      = Events.onClick
  val v: AttrValue            = AttrValue.Str("x")
  val codec: Codec[String]    = Codec.StringAsIs

  val aliasedDiv: ElementKey        = E.div
  val aliasedClass: AttrKey[String] = A.className
  val aliasedRole: AttrKey[String]  = Aria.role

  def spec = suite("ascent.* facade (dom-types)")(
    test("aliases are the very same objects as their full names (no drift)") {
      assertTrue(
        (E: AnyRef) eq (Elements: AnyRef),
        (A: AnyRef) eq (Attrs: AnyRef),
        (Aria: AnyRef) eq (AriaAttrs: AnyRef),
      )
    },
    test("re-exported keys carry the right dom names") {
      assertTrue(
        divK.domName == "div",
        classK.domName == "class",
        roleK.domName == "role",
        onClickK.domName == "click",
      )
    },
    test("AttrValue + Codec are reachable through the facade") {
      assertTrue(
        v == AttrValue.Str("x"),
        Codec.StringAsIs.encode("y") == AttrValue.Str("y"),
      )
    },
  )
end FacadeSpec
