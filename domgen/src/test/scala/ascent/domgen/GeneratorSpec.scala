package ascent.domgen

import zio.*
import zio.test.*

import scala.io.Source

object GeneratorSpec extends ZIOSpecDefault:

  /** Reads a small fixture file from `src/test/resources/webref/`. */
  private def resource(name: String): String =
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/webref/$name"), "UTF-8")
    try src.mkString
    finally src.close()

  /** Same fixture inputs the parser/builder specs use, parsed once and assembled into the single [[GeneratorInput]] the
    * pipeline takes. Fixtures cover: 4 elements (incl. void br/input), 4 events (`click`/`keydown`/`input`/`abort`),
    * and an IDL graph spanning Event → UIEvent → MouseEvent → PointerEvent + KeyboardEvent + InputEvent.
    */
  private val inputZ: UIO[GeneratorInput] =
    for
      els <- Webref
        .parseElements(resource("elements-html.json"))
        .orDieWith(e => new RuntimeException(e.detail))
      evs <- Webref
        .parseEvents(resource("events.json"))
        .orDieWith(e => new RuntimeException(e.detail))
      idlA <- Webref
        .parseIdl(resource("idlparsed-html.json"))
        .orDieWith(e => new RuntimeException(e.detail))
      idlB <- Webref
        .parseIdl(resource("idlparsed-uievents.json"))
        .orDieWith(e => new RuntimeException(e.detail))
    yield GeneratorInput(
      elements = els,
      events = evs,
      idl = Webref.mergeIdl(idlA, idlB),
      eventAllowlist = Set("click", "keydown", "input", "abort"), // matches the events fixture
      strictElements = Set("input"),                              // only input gets per-element typed attrs
    )

  def spec = suite("Generator pipeline")(
    test("emits one generated file per output target with the expected names") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield assertTrue(
        out.files.keySet == Set(
          "dom-types/Elements.scala",
          "dom-types/Attrs.scala",
          "dom-types/Events.scala",
          "dom-types/Enums.scala",
          "dom-facade/Facades.scala",
          "dom-facade/Interfaces.scala",
          "dom-facade/Dictionaries.scala",
          "dom-facade/EnumAccessors.scala",
          "js/TypedEvents.scala",
          "dom-core/Elements.scala",
          "dom-core/ElementsMemory.scala",
          "dom-core/ElementFactory.scala",
        )
      )
    },
    test("Dictionaries.scala contains a trait for every IDL dictionary") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val traitRe  = "(?m)^trait (\\w+)".r
        val emitted  = traitRe.findAllMatchIn(out.files("dom-facade/Dictionaries.scala")).map(_.group(1)).toSet
        val expected = input.idl.dictionaries.map(_.name).toSet
        val missing  = expected -- emitted
        assertTrue(missing.isEmpty)
    },
    test("Enums.scala (dom-types) contains a real `enum` for every IDL enum") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val enumRe   = "(?m)^enum (\\w+)".r
        val emitted  = enumRe.findAllMatchIn(out.files("dom-types/Enums.scala")).map(_.group(1)).toSet
        val expected = input.idl.enums.map(_.name).toSet
        val missing  = expected -- emitted
        assertTrue(missing.isEmpty)
    },
    test("Interfaces.scala contains a class for every non-mixin, non-event interface in the IDL") {
      // Coverage gate: a failure means the vendored snapshot got a new interface the generator
      // silently dropped.
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val classRe          = "(?m)^class (\\w+)".r
        val emittedNames     = classRe.findAllMatchIn(out.files("dom-facade/Interfaces.scala")).map(_.group(1)).toSet
        val eventFacadeNames = classRe.findAllMatchIn(out.files("dom-facade/Facades.scala")).map(_.group(1)).toSet
        val expected         = input.idl.interfaces.collect {
          case (name, iface) if !iface.isMixin && !eventFacadeNames.contains(name) => name
        }.toSet
        val missing = expected -- emittedNames
        assertTrue(missing.isEmpty)
    },
    test("Elements.scala emits ElementKey for non-void and VoidElementKey for void elements") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-types/Elements.scala")
        assertTrue(
          src.contains("""val div: ElementKey = ElementKey("div")"""),
          src.contains("""val span: ElementKey = ElementKey("span")"""),
          src.contains("""val br: VoidElementKey = VoidElementKey("br")"""),
          src.contains("""val input: VoidElementKey = VoidElementKey("input")"""),
        )
    },
    test("Attrs.scala contains attrs for the strict element walked through IDL inheritance") {
      // input's IDL chain (HTMLInputElement -> HTMLElement -> Element) contributes own + inherited attrs.
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-types/Attrs.scala")
        assertTrue(
          src.contains("""val `type`: AttrKey[String] = AttrKey("type", Codec.StringAsIs)"""),
          src.contains("""val required: AttrKey[Boolean] = AttrKey("required", Codec.BooleanAsAttrPresence)"""),
          src.contains("""val hidden: AttrKey[Boolean] = AttrKey("hidden", Codec.BooleanAsAttrPresence)"""),
          src.contains("""val id: AttrKey[String] = AttrKey("id", Codec.StringAsIs)"""),
        )
    },
    test("Events.scala only contains keys for events on the allowlist") {
      val limited = inputZ.map(_.copy(eventAllowlist = Set("click", "keydown")))
      for
        input <- limited
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-types/Events.scala")
        assertTrue(
          src.contains("""val onClick: EventKey = EventKey("click", "ascent.dom.PointerEvent")"""),
          src.contains("""val onKeyDown: EventKey = EventKey("keydown", "ascent.dom.KeyboardEvent")"""),
          !src.contains("""onAbort"""),
          !src.contains("""onInput"""),
        )
      end for
    },
    test("Facades.scala emits the transitive ancestor chain even if no event names them directly") {
      // click is a PointerEvent (extends MouseEvent → UIEvent → Event); all four must appear as facades.
      val onlyClick = inputZ.map(_.copy(eventAllowlist = Set("click")))
      for
        input <- onlyClick
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-facade/Facades.scala")
        assertTrue(
          src.contains("class PointerEvent extends MouseEvent"),
          src.contains("class MouseEvent extends UIEvent"),
          src.contains("class UIEvent extends Event"),
          src.contains("class Event extends js.Object"),
        )
      end for
    },
    test("collapses duplicate event-type entries to the most-specific interface in the IDL chain") {
      // webref's events.json lists the same event type under multiple interfaces (e.g. `abort` on both
      // Event and ProgressEvent); without dedup the generator emits the val name twice. The canonical
      // pick is the deepest interface in the chain.
      val withDuplicateAbort = inputZ.map { i =>
        val abortGeneric = Webref.Event(`type` = "abort", interface = "Event")
        val abortMedia   = Webref.Event(`type` = "abort", interface = "MouseEvent")
        // MouseEvent isn't the spec-correct parent of `abort`; it's used only because the fixture's IDL
        // makes it deeper than Event. The test is about the dedup mechanism, not the spec mapping.
        i.copy(
          events = abortGeneric :: abortMedia :: i.events.filter(_.`type` != "abort"),
          eventAllowlist = i.eventAllowlist + "abort",
        )
      }
      for
        input <- withDuplicateAbort
        out   <- Generator.run(input)
      yield
        val src        = out.files("dom-types/Events.scala")
        val abortLines = src.linesIterator.filter(_.contains("onAbort:")).toList
        assertTrue(
          abortLines.size == 1,                              // exactly one val emitted
          abortLines.head.contains("ascent.dom.MouseEvent"), // the deeper interface won
          !abortLines.head.contains("ascent.dom.Event\""),
        )
      end for
    },
    test("an unknown event interface fails generation loudly with a typed error") {
      val withMissing = inputZ.map { i =>
        val ev = Webref.Event(`type` = "fakeevent", interface = "TotallyMadeUpEvent")
        i.copy(events = ev :: i.events, eventAllowlist = i.eventAllowlist + "fakeevent")
      }
      for
        input <- withMissing
        exit  <- Generator.run(input).exit
      yield assert(exit)(Assertion.failsWithA[GeneratorError])
    },
    test("dom-core Elements.scala covers every element root's interface plus its ancestor chain") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-core/Elements.scala")
        assertTrue(
          src.contains("trait HTMLInputElement extends HTMLElement"),
          src.contains("trait HTMLElement extends Element"),
          src.contains("trait Element extends Node"),
        )
    },
    test("dom-core Elements.scala has zero js.native / scalajs references — compiles on jvm/js/native") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-core/Elements.scala")
        assertTrue(!src.contains("js.native"), !src.contains("scalajs"), !src.contains("@JSGlobal"))
    },
    test("dom-core ElementsMemory.scala generates a Memory class per structural interface, chaining via extends") {
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-core/ElementsMemory.scala")
        assertTrue(
          src.contains("class HTMLInputElementMemory extends HTMLElementMemory with HTMLInputElement"),
          src.contains("class HTMLElementMemory extends ElementMemory with HTMLElement"),
        )
    },
    test("dom-core ElementsMemory.scala auto-implements a [Reflect]-marked attribute via the attribute map") {
      // The fixture's HTMLInputElement.type carries [Reflect], so the memory generator auto-implements it.
      for
        input <- inputZ
        out   <- Generator.run(input)
      yield
        val src = out.files("dom-core/ElementsMemory.scala")
        assertTrue(
          src.contains("""attributeMap.getOrElse("type", "")"""),
          src.contains("""attributeMap.set("type", value)"""),
        )
    },
  )
end GeneratorSpec
