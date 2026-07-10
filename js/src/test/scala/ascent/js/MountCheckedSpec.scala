package ascent.js

import ascent.dom
import ascent.domtypes.{Attrs, Elements}
import ascent.dsl.*
import ascent.squawk.{Eq, sq}
import zio.*
import zio.test.*

import scala.scalajs.js

/** `Attrs.checked(squawk)` must drive the checkbox's live `checked` PROPERTY, not just the attribute. The risk: the
  * `checked` codec is `BooleanAsAttrPresence` (encodes to `Str("")`/`Absent`, never `Bool`), so Mount.setAttr
  * special-cases the "checked" name to route both onto `el.checked`. This pins that the typed-key DSL path preserves
  * it.
  */
object MountCheckedSpec extends ZIOSpecDefault:

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (checked property via Attrs.checked(squawk))")(
    test("initial true renders the live checked property as true") {
      withParent { parent =>
        for
          on <- sq(true)
          ui = Elements.input(Attrs.`type`("checkbox"), Attrs.checked(on))
          _ <- AscentApp.mount(ui, parent)
        yield
          val input = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(input.checked.asInstanceOf[Boolean] == true)
      }
    },
    test("toggling the squawk flips the live checked property (not merely the attribute)") {
      withParent { parent =>
        for
          on <- sq(false)
          ui = ascent.domtypes.Elements.input(Attrs.`type`("checkbox"), Attrs.checked(on))
          _ <- AscentApp.mount(ui, parent)
          input  = parent.asInstanceOf[js.Dynamic].firstChild
          before = input.checked.asInstanceOf[Boolean]
          _ <- on.set(true)
          afterOn = input.checked.asInstanceOf[Boolean]
          _ <- on.set(false)
          afterOff = input.checked.asInstanceOf[Boolean]
        yield assertTrue(before == false, afterOn == true, afterOff == false)
      }
    },
    test("pathological: initial false leaves the property false (no spurious check)") {
      withParent { parent =>
        for
          on <- sq(false)
          ui = ascent.domtypes.Elements.input(Attrs.`type`("checkbox"), Attrs.checked(on))
          _ <- AscentApp.mount(ui, parent)
        yield
          val input = parent.asInstanceOf[js.Dynamic].firstChild
          assertTrue(input.checked.asInstanceOf[Boolean] == false)
      }
    },
  )
end MountCheckedSpec
