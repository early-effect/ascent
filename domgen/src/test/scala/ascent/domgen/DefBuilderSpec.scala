package ascent.domgen

import zio.*
import zio.test.*

import scala.io.Source

object DefBuilderSpec extends ZIOSpecDefault:

  private def resource(name: String): String =
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/webref/$name"), "UTF-8")
    try src.mkString
    finally src.close()

  private val elements = Webref.parseElements(resource("elements-html.json"))
  private val events   = Webref.parseEvents(resource("events.json"))
  private val idl      =
    for
      html <- Webref.parseIdl(resource("idlparsed-html.json"))
      ui   <- Webref.parseIdl(resource("idlparsed-uievents.json"))
    yield Webref.mergeIdl(html, ui)

  def spec = suite("DefBuilder")(
    suite("element defs")(
      test("maps dom name to a safe scala name and carries the interface") {
        for es <- elements
        yield
          val byName = DefBuilder.elementDefs(es).map(d => d.domName -> d).toMap
          assertTrue(
            byName("div").scalaName == "div",
            byName("div").interface == "HTMLDivElement",
          )
      },
      test("flags void elements (br) and not non-void (div, span, input)") {
        for es <- elements
        yield
          val byName = DefBuilder.elementDefs(es).map(d => d.domName -> d.isVoid).toMap
          assertTrue(
            byName("br") == true,
            byName("input") == true,
            byName("div") == false,
            byName("span") == false,
          )
      },
    ),
    suite("per-element attributes (IDL inheritance walk)")(
      test("input includes its own attrs plus inherited from HTMLElement and Element") {
        for i <- idl
        yield
          val attrs = DefBuilder.attributesFor("HTMLInputElement", i).map(_.domName).toSet
          assertTrue(
            attrs.contains("type"),     // own
            attrs.contains("required"), // own
            attrs.contains("hidden"),   // from HTMLElement
            attrs.contains("title"),    // from HTMLElement
            attrs.contains("id"),       // from Element
          )
      },
      test("className is exposed as scala `className` but its DOM (attribute) name is `class`") {
        // The IDL JS-property name (`className`) is what user code sees; domName must be the HTML
        // attribute name (`class`) since that's what setAttribute receives.
        for i <- idl
        yield
          val byScala = DefBuilder.attributesFor("HTMLInputElement", i).map(a => a.scalaName -> a).toMap
          assertTrue(
            byScala.contains("className"),
            byScala("className").domName == "class",
            byScala("className").scalaName == "className",
          )
      },
      test("boolean IDL attrs get a boolean-presence codec; DOMString attrs get StringAsIs") {
        for i <- idl
        yield
          val byName = DefBuilder.attributesFor("HTMLInputElement", i).map(a => a.domName -> a.codec).toMap
          assertTrue(
            byName("required") == CodecRef.BooleanAsAttrPresence,
            byName("type") == CodecRef.StringAsIs,
          )
      },
      test("enumerated true/false boolean attrs (draggable, spellcheck) use BooleanAsTrueFalse, NOT presence") {
        // `draggable` / `spellcheck` are enumerated attrs: their only valid values are the literal
        // strings "true"/"false". Presence-coding would emit `draggable=""`, which the HTML spec reads
        // as the "auto" state (NOT draggable) — so they must serialize the explicit strings instead.
        val syntheticIdl = Webref.Idl(
          Map(
            "TestEl" -> Webref.IdlInterface(
              name = "TestEl",
              inheritance = None,
              attributes = List(
                Webref.IdlAttribute("draggable", "boolean"),
                Webref.IdlAttribute("spellcheck", "boolean"),
                Webref.IdlAttribute("required", "boolean"), // genuine presence boolean (control)
              ),
            )
          )
        )
        val byName = DefBuilder.attributesFor("TestEl", syntheticIdl).map(a => a.domName -> a.codec).toMap
        assertTrue(
          byName("draggable") == CodecRef.BooleanAsTrueFalse,
          byName("spellcheck") == CodecRef.BooleanAsTrueFalse,
          // A *genuine* presence boolean is unaffected: `required` stays present-or-absent.
          byName("required") == CodecRef.BooleanAsAttrPresence,
        )
      },
      test("a cycle or missing parent terminates the walk without looping forever") {
        // Node -> EventTarget, but EventTarget is absent from the fixture: walk must stop cleanly.
        for i <- idl
        yield assertTrue(DefBuilder.attributesFor("HTMLInputElement", i).nonEmpty)
      },
      test("mixin attributes (e.g. `autofocus`, `contentEditable`, `dataset`) are surfaced via `includes` statements") {
        // webref defines `autofocus` on `interface mixin HTMLOrSVGOrMathMLElement`, tied to HTMLElement
        // via a top-level `HTMLElement includes ...;` — the walk must decode both and merge the members.
        for i <- idl
        yield
          val attrs = DefBuilder.attributesFor("HTMLInputElement", i).map(_.scalaName).toSet
          assertTrue(
            attrs.contains("autofocus"),
            attrs.contains("contentEditable"),
            attrs.contains("nonce"),
          )
      },
      test("methodsFor walks inheritance + mixins, surfacing every operation reachable from the interface") {
        val syntheticIdl = Webref.Idl(
          Map(
            "Mixin" -> Webref.IdlInterface(
              name = "Mixin",
              inheritance = None,
              attributes = Nil,
              isMixin = true,
              operations = List(Webref.IdlOperation("fromMixin", "undefined", Nil)),
            ),
            "Base" -> Webref.IdlInterface(
              name = "Base",
              inheritance = None,
              attributes = Nil,
              operations = List(Webref.IdlOperation("fromBase", "undefined", Nil)),
            ),
            "Child" -> Webref.IdlInterface(
              name = "Child",
              inheritance = Some("Base"),
              attributes = Nil,
              operations = List(
                Webref.IdlOperation("fromChild", "DOMString", List(Webref.IdlParam("arg", "long", false)))
              ),
            ),
          ),
          includes = List(Webref.IdlIncludes(target = "Base", mixin = "Mixin")),
        )
        val methods = DefBuilder.methodsFor("Child", syntheticIdl).map(_.scalaName).toSet
        assertTrue(
          methods.contains("fromChild"),
          methods.contains("fromBase"),
          methods.contains("fromMixin"),
        )
      },
      test("interfaceDefs returns one InterfaceDef per non-mixin interface, skipping engine-owned + event facades") {
        // Mixins are never emitted by name (folded into their including interfaces); engine-owned
        // interfaces are skipped so the hand-written EngineFacade.scala stays the single source of truth.
        val idl = Webref.Idl(
          Map(
            "HTMLCanvasElement"        -> Webref.IdlInterface("HTMLCanvasElement", Some("HTMLElement"), Nil),
            "HTMLElement"              -> Webref.IdlInterface("HTMLElement", Some("Element"), Nil),
            "Element"                  -> Webref.IdlInterface("Element", None, Nil),
            "Mixin"                    -> Webref.IdlInterface("Mixin", None, Nil, isMixin = true),
            "Event"                    -> Webref.IdlInterface("Event", None, Nil), // event facade
            "CanvasRenderingContext2D" -> Webref.IdlInterface("CanvasRenderingContext2D", None, Nil),
          )
        )
        val engineOwned  = Set("Element", "EventTarget", "Node", "Document", "Text", "Comment")
        val eventFacades = Set("Event")
        val defs         = DefBuilder.interfaceDefs(idl, skipNames = engineOwned ++ eventFacades).map(_.name).toSet
        assertTrue(
          defs.contains("HTMLCanvasElement"),
          defs.contains("HTMLElement"),
          defs.contains("CanvasRenderingContext2D"),
          // Filtered out:
          !defs.contains("Element"), // engine-owned
          !defs.contains("Event"),   // event facade
          !defs.contains("Mixin"),   // mixin (always)
        )
      },
      test("InterfaceDef carries attributes + methods (own + mixin merged)") {
        val idl = Webref.Idl(
          Map(
            "Iface" -> Webref.IdlInterface(
              "Iface",
              None,
              attributes = List(Webref.IdlAttribute("alpha", "DOMString")),
              operations = List(
                Webref.IdlOperation("doIt", "undefined", List(Webref.IdlParam("n", "long", false)))
              ),
            )
          )
        )
        val def_ = DefBuilder.interfaceDefs(idl, skipNames = Set.empty).find(_.name == "Iface").get
        assertTrue(
          def_.attributes.map(_.name).toSet == Set("alpha"),
          def_.methods.map(_.scalaName).toSet == Set("doIt"),
          def_.methods.head.params.head.scalaType == "Int",
          def_.methods.head.returnType == "Unit",
        )
      },
      test("InterfaceDef.parent points at the inheritance ancestor for the `extends` clause") {
        val idl = Webref.Idl(
          Map(
            "Child"  -> Webref.IdlInterface("Child", Some("Parent"), Nil),
            "Parent" -> Webref.IdlInterface("Parent", None, Nil),
          )
        )
        val byName = DefBuilder.interfaceDefs(idl, skipNames = Set.empty).map(d => d.name -> d).toMap
        assertTrue(
          byName("Child").parent == Some("Parent"),
          byName("Parent").parent == None,
        )
      },
      test("InterfaceDef parent that points at an unknown / skipped interface falls back to None") {
        // A parent the generator doesn't emit (skipped or missing) must resolve to None so the
        // emitted class still compiles, falling back to `extends js.Object`.
        val idl = Webref.Idl(
          Map(
            "Child" -> Webref.IdlInterface("Child", Some("MissingParent"), Nil)
          )
        )
        val def_ = DefBuilder.interfaceDefs(idl, skipNames = Set.empty).head
        assertTrue(def_.parent == None)
      },
      test("dictionaryDefs builds typed traits with own + inherited fields") {
        val idl = Webref.Idl(
          interfaces = Map.empty,
          dictionaries = List(
            Webref.IdlDictionary(
              "EventListenerOptions",
              None,
              List(
                Webref.IdlField("capture", "boolean", required = false)
              ),
            ),
            Webref.IdlDictionary(
              "AddEventListenerOptions",
              Some("EventListenerOptions"),
              List(
                Webref.IdlField("passive", "boolean", required = false),
                Webref.IdlField("once", "boolean", required = false),
                Webref.IdlField("signal", "AbortSignal", required = false),
              ),
            ),
          ),
        )
        val defs = DefBuilder.dictionaryDefs(idl).map(d => d.name -> d).toMap
        val opts = defs("AddEventListenerOptions")
        assertTrue(
          opts.parent == Some("EventListenerOptions"),
          opts.fields.map(_.name) == List("passive", "once", "signal"),
          opts.fields.map(_.scalaType) == List(
            "Boolean",
            "Boolean",
            "scala.scalajs.js.Any",
          ), // AbortSignal is in idl.interfaces? not in this synthetic — falls to js.Any
          // Required flag is preserved so the renderer can decide default-arg vs no-default.
          opts.fields.forall(!_.required),
        )
      },
      test("enumDefs surfaces every value as a stable Scala name + dom literal") {
        val idl = Webref.Idl(
          interfaces = Map.empty,
          enums = List(
            Webref.IdlEnum("ShadowRootMode", List("open", "closed")),
            // Hyphenated values get camel-cased Scala names; the dom value stays as-is.
            Webref.IdlEnum("EndingType", List("transparent", "native", "very-bad")),
          ),
        )
        val defs   = DefBuilder.enumDefs(idl).map(d => d.name -> d).toMap
        val mode   = defs("ShadowRootMode")
        val ending = defs("EndingType")
        assertTrue(
          mode.values == List("open" -> "open", "closed" -> "closed"),
          ending.values == List("transparent" -> "transparent", "native" -> "native", "veryBad" -> "very-bad"),
        )
      },
      test("a method parameter typed as a callback (e.g. FrameRequestCallback) maps to js.Function1[..., Unit]") {
        val idl = Webref.Idl(
          interfaces = Map(
            "Window" -> Webref.IdlInterface(
              "Window",
              None,
              Nil,
              operations = List(
                Webref.IdlOperation(
                  "requestAnimationFrame",
                  "unsigned long",
                  List(Webref.IdlParam("callback", "FrameRequestCallback", false)),
                ),
                Webref.IdlOperation(
                  "addEventListener",
                  "undefined",
                  List(
                    Webref.IdlParam("type", "DOMString", false),
                    Webref.IdlParam("listener", "EventListener", false),
                  ),
                ),
              ),
            ),
            // Stub `Event` so the type mapper resolves `EventListener`'s `event: Event`
            // param to the bare interface name rather than falling through to `js.Any`.
            "Event" -> Webref.IdlInterface("Event", None, Nil),
          ),
          callbacks = List(
            Webref.IdlCallback("FrameRequestCallback", "undefined", List(Webref.IdlParam("time", "double", false))),
            Webref.IdlCallback("EventListener", "undefined", List(Webref.IdlParam("event", "Event", false))),
          ),
        )
        val byName = DefBuilder.methodsFor("Window", idl).map(m => m.scalaName -> m).toMap
        val raf    = byName("requestAnimationFrame")
        val ael    = byName("addEventListener")
        assertTrue(
          raf.params.map(_.scalaType) == List("scala.scalajs.js.Function1[Double, Unit]"),
          ael.params.map(_.scalaType) == List("String", "scala.scalajs.js.Function1[Event, Unit]"),
        )
      },
      test("MethodDef captures the return type AND each parameter, with IDL → Scala type mapping") {
        val idl = Webref.Idl(
          Map(
            "X" -> Webref.IdlInterface(
              name = "X",
              inheritance = None,
              attributes = Nil,
              operations =
                List(
                  Webref.IdlOperation(
                    "fillRect",
                    "undefined",
                    List(
                      Webref.IdlParam("x", "unrestricted double", false),
                      Webref.IdlParam("y", "unrestricted double", false),
                      Webref.IdlParam("w", "unrestricted double", false),
                      Webref.IdlParam("h", "unrestricted double", false),
                    ),
                  ),
                  Webref.IdlOperation(
                    "getContext",
                    "RenderingContext",
                    List(Webref.IdlParam("contextId", "DOMString", false)),
                  ),
                ),
            )
          )
        )
        val byName   = DefBuilder.methodsFor("X", idl).map(m => m.scalaName -> m).toMap
        val fillRect = byName("fillRect")
        val getCtx   = byName("getContext")
        assertTrue(
          fillRect.returnType == "Unit",
          fillRect.params.map(_.name) == List("x", "y", "w", "h"),
          fillRect.params.map(_.scalaType) == List("Double", "Double", "Double", "Double"),
          getCtx.returnType == "scala.scalajs.js.Any", // RenderingContext is a typedef union; surface as js.Any until dictionaries land
          getCtx.params.map(_.scalaType) == List("String"),
        )
      },
      test("attributesFor merges members from both direct inheritance AND an `includes` mixin") {
        val syntheticIdl = Webref.Idl(
          Map(
            "Mixin" -> Webref.IdlInterface(
              name = "Mixin",
              inheritance = None,
              isMixin = true,
              attributes = List(Webref.IdlAttribute("fromMixin", "DOMString")),
            ),
            "Base" -> Webref.IdlInterface(
              name = "Base",
              inheritance = None,
              attributes = List(Webref.IdlAttribute("fromBase", "DOMString")),
            ),
            "Child" -> Webref.IdlInterface(
              name = "Child",
              inheritance = Some("Base"),
              attributes = List(Webref.IdlAttribute("fromChild", "DOMString")),
            ),
          ),
          includes = List(Webref.IdlIncludes(target = "Base", mixin = "Mixin")),
        )
        val attrs = DefBuilder.attributesFor("Child", syntheticIdl).map(_.scalaName).toSet
        assertTrue(
          attrs.contains("fromChild"), // own
          attrs.contains("fromBase"),  // inherited via parent chain
          attrs.contains("fromMixin"), // inherited via Base includes Mixin
        )
      },
      test("structural IDL-property-to-HTML-attribute renames apply (className/htmlFor/tabIndex/readOnly/etc.)") {
        val syntheticIdl = Webref.Idl(
          Map(
            "TestEl" -> Webref.IdlInterface(
              name = "TestEl",
              inheritance = None,
              attributes = List(
                Webref.IdlAttribute("className", "DOMString"),
                Webref.IdlAttribute("htmlFor", "DOMString"),
                Webref.IdlAttribute("tabIndex", "long"),
                Webref.IdlAttribute("readOnly", "boolean"),
                Webref.IdlAttribute("maxLength", "long"),
                Webref.IdlAttribute("contentEditable", "DOMString"),
                Webref.IdlAttribute("accessKey", "DOMString"),
                Webref.IdlAttribute("id", "DOMString"), // no rename: already lowercase
              ),
            )
          )
        )
        val byScala = DefBuilder.attributesFor("TestEl", syntheticIdl).map(a => a.scalaName -> a.domName).toMap
        assertTrue(
          byScala("className") == "class",
          byScala("htmlFor") == "for",
          byScala("tabIndex") == "tabindex",
          byScala("readOnly") == "readonly",
          byScala("maxLength") == "maxlength",
          byScala("contentEditable") == "contenteditable",
          byScala("accessKey") == "accesskey",
          byScala("id") == "id",
        )
      },
    ),
    suite("event defs + facade mapping")(
      test("maps each event interface to our ascent.dom facade type string") {
        for es <- events
        yield
          val byType = DefBuilder.eventDefs(es).map(d => d.domName -> d.eventTypeString).toMap
          assertTrue(
            byType("click") == "ascent.dom.PointerEvent",
            byType("keydown") == "ascent.dom.KeyboardEvent",
            byType("input") == "ascent.dom.InputEvent",
            byType("abort") == "ascent.dom.Event",
          )
      },
      test("derives a scala handler name like onClick / onKeyDown from the event type") {
        for es <- events
        yield
          val byType = DefBuilder.eventDefs(es).map(d => d.domName -> d.scalaName).toMap
          assertTrue(
            byType("click") == "onClick",
            byType("keydown") == "onKeyDown",
          )
      },
      test("carries bubbles, defaulting to false when no target declares it") {
        for es <- events
        yield
          val byType = DefBuilder.eventDefs(es).map(d => d.domName -> d.bubbles).toMap
          assertTrue(byType("click") == true, byType("abort") == false)
      },
    ),
    suite("facade defs (event interface hierarchy)")(
      test("builds a facade per event interface with its own members and parent") {
        for
          es <- events
          i  <- idl
        yield
          val facades = DefBuilder.facadeDefs(es, i).map(f => f.interface -> f).toMap
          assertTrue(
            facades("MouseEvent").parent == Some("UIEvent"),
            facades("MouseEvent").members.map(_.name).toSet == Set("clientX", "clientY"),
            facades("Event").parent == None,
          )
      },
      test(
        "includes transitively-referenced ancestor interfaces (UIEvent, Event) even if no event names them directly"
      ) {
        for
          es <- events
          i  <- idl
        yield
          val facades = DefBuilder.facadeDefs(es, i).map(_.interface).toSet
          assertTrue(facades.contains("UIEvent"), facades.contains("Event"))
      },
      test("maps IDL member types to scala facade types (double->Double, DOMString->String, long->Int)") {
        for
          es <- events
          i  <- idl
        yield
          val mouse = DefBuilder.facadeDefs(es, i).find(_.interface == "MouseEvent").get
          val kbd   = DefBuilder.facadeDefs(es, i).find(_.interface == "KeyboardEvent").get
          val ui    = DefBuilder.facadeDefs(es, i).find(_.interface == "UIEvent").get
          assertTrue(
            mouse.members.find(_.name == "clientX").get.scalaType == "Double",
            kbd.members.find(_.name == "key").get.scalaType == "String",
            ui.members.find(_.name == "detail").get.scalaType == "Int",
          )
      },
      test("a facade ATTRIBUTE typed as a known interface resolves to that interface, not js.Any") {
        // Members must get the same idl-aware resolution as operations: a known interface name resolves
        // to the bare interface type, not the opaque js.Any that would force `.asInstanceOf` at call sites.
        val idl = Webref.Idl(
          Map(
            "DragEvent" -> Webref.IdlInterface(
              "DragEvent",
              inheritance = Some("MouseEvent"),
              attributes = List(Webref.IdlAttribute("dataTransfer", "DataTransfer", readonly = true)),
            ),
            "MouseEvent"   -> Webref.IdlInterface("MouseEvent", inheritance = None, attributes = Nil),
            "DataTransfer" -> Webref.IdlInterface("DataTransfer", inheritance = None, attributes = Nil),
          )
        )
        val events = List(Webref.Event(`type` = "drop", interface = "DragEvent"))
        val drag   = DefBuilder.facadeDefs(events, idl).find(_.interface == "DragEvent").get
        assertTrue(
          drag.members.find(_.name == "dataTransfer").get.scalaType == "DataTransfer"
        )
      },
      test("a facade attribute typed as an UNKNOWN name still falls back to js.Any") {
        val idl = Webref.Idl(
          Map(
            "WeirdEvent" -> Webref.IdlInterface(
              "WeirdEvent",
              inheritance = None,
              attributes = List(Webref.IdlAttribute("blob", "SomeUnmodelledUnion", readonly = true)),
            )
          )
        )
        val events = List(Webref.Event(`type` = "weird", interface = "WeirdEvent"))
        val weird  = DefBuilder.facadeDefs(events, idl).find(_.interface == "WeirdEvent").get
        assertTrue(
          weird.members.find(_.name == "blob").get.scalaType == "scala.scalajs.js.Any"
        )
      },
      test("event facades carry their operations too (preventDefault, stopPropagation, composedPath, ...)") {
        val idl = Webref.Idl(
          Map(
            "Event" -> Webref.IdlInterface(
              "Event",
              None,
              attributes = List(Webref.IdlAttribute("type", "DOMString", readonly = true)),
              operations = List(
                Webref.IdlOperation("preventDefault", "undefined", Nil),
                Webref.IdlOperation("stopPropagation", "undefined", Nil),
              ),
            )
          )
        )
        val events  = List(Webref.Event(`type` = "click", interface = "Event"))
        val facades = DefBuilder.facadeDefs(events, idl)
        val event   = facades.find(_.interface == "Event").get
        assertTrue(
          event.methods.map(_.scalaName).toSet == Set("preventDefault", "stopPropagation"),
          event.methods.head.returnType == "Unit",
        )
      },
      test("drops a member that would conflict with an inherited member of a different scala type") {
        // Real case: BeforeUnloadEvent.returnValue is DOMString while parent Event.returnValue is
        // boolean (legacy). Emitting both is an illegal override (String can't override Boolean), so
        // the conflicting child member is dropped. Synthesised with `flag` to avoid webref churn.
        val parent = Webref.IdlInterface(
          name = "Parent",
          inheritance = None,
          attributes = List(Webref.IdlAttribute("flag", "boolean")),
        )
        val child = Webref.IdlInterface(
          name = "Child",
          inheritance = Some("Parent"),
          attributes = List(
            Webref.IdlAttribute("flag", "DOMString"),   // conflicts with Parent.flag (Boolean)
            Webref.IdlAttribute("ownAttr", "DOMString"), // unrelated, must survive
          ),
        )
        val idlMap      = Webref.Idl(Map("Parent" -> parent, "Child" -> child))
        val events      = List(Webref.Event(`type` = "x", interface = "Child"))
        val facades     = DefBuilder.facadeDefs(events, idlMap)
        val childFacade = facades.find(_.interface == "Child").get
        val members     = childFacade.members.map(m => m.name -> m.scalaType).toMap
        assertTrue(
          !members.contains("flag"),               // dropped because of type conflict
          members.get("ownAttr") == Some("String"), // unrelated member survives
        )
      },
    ),
    suite("elementInterfaceClosure (structural DOM catalog scoping)")(
      test("includes every element root's interface plus its full ancestor chain") {
        val idl = Webref.Idl(
          Map(
            "HTMLInputElement" -> Webref.IdlInterface("HTMLInputElement", Some("HTMLElement"), Nil),
            "HTMLElement"      -> Webref.IdlInterface("HTMLElement", Some("Element"), Nil),
            "Element"          -> Webref.IdlInterface("Element", Some("Node"), Nil),
            "Node"             -> Webref.IdlInterface("Node", Some("EventTarget"), Nil),
            "EventTarget"      -> Webref.IdlInterface("EventTarget", None, Nil),
            // Unrelated interface — must NOT be pulled in just by existing in the IDL map.
            "Performance" -> Webref.IdlInterface("Performance", None, Nil),
          )
        )
        val roots   = List(Webref.Element("input", "HTMLInputElement"))
        val closure = DefBuilder.elementInterfaceClosure(roots, Nil, idl)
        assertTrue(
          closure.contains("HTMLInputElement"),
          closure.contains("HTMLElement"),
          closure.contains("Element"),
          closure.contains("Node"),
          closure.contains("EventTarget"),
          !closure.contains("Performance"),
        )
      },
      test("forces in the core interfaces even when no element root's chain reaches them") {
        // Document/Attr/Event aren't ancestors of any HTMLElement, but dom-core's structural traits
        // need them unconditionally, so the closure includes them regardless.
        val idl = Webref.Idl(
          Map("HTMLDivElement" -> Webref.IdlInterface("HTMLDivElement", None, Nil))
        )
        val roots   = List(Webref.Element("div", "HTMLDivElement"))
        val closure = DefBuilder.elementInterfaceClosure(roots, Nil, idl)
        assertTrue(
          closure.contains("Document"),
          closure.contains("Attr"),
          closure.contains("Event"),
          closure.contains("CharacterData"),
          closure.contains("Text"),
          closure.contains("Comment"),
          closure.contains("NodeList"),
          closure.contains("DOMTokenList"),
          closure.contains("HTMLCollection"),
          closure.contains("NamedNodeMap"),
        )
      },
      test("merges HTML and SVG element roots into one closure") {
        val idl = Webref.Idl(
          Map(
            "HTMLDivElement"     -> Webref.IdlInterface("HTMLDivElement", None, Nil),
            "SVGCircleElement"   -> Webref.IdlInterface("SVGCircleElement", Some("SVGGeometryElement"), Nil),
            "SVGGeometryElement" -> Webref.IdlInterface("SVGGeometryElement", None, Nil),
          )
        )
        val html    = List(Webref.Element("div", "HTMLDivElement"))
        val svg     = List(Webref.Element("circle", "SVGCircleElement"))
        val closure = DefBuilder.elementInterfaceClosure(html, svg, idl)
        assertTrue(
          closure.contains("HTMLDivElement"),
          closure.contains("SVGCircleElement"),
          closure.contains("SVGGeometryElement"),
        )
      },
      test("a cycle in the ancestor chain terminates the walk without looping forever") {
        val idl = Webref.Idl(
          Map(
            "A" -> Webref.IdlInterface("A", Some("B"), Nil),
            "B" -> Webref.IdlInterface("B", Some("A"), Nil), // cycle
          )
        )
        val roots   = List(Webref.Element("a-tag", "A"))
        val closure = DefBuilder.elementInterfaceClosure(roots, Nil, idl)
        assertTrue(closure.contains("A"), closure.contains("B"))
      },
      test("an element root whose interface is missing from the IDL doesn't crash the walk") {
        val idl     = Webref.Idl(Map.empty)
        val roots   = List(Webref.Element("mystery", "TotallyUnknownInterface"))
        val closure = DefBuilder.elementInterfaceClosure(roots, Nil, idl)
        // The root name is still recorded so a later interfaceDefs lookup can report it missing,
        // rather than the closure silently pretending the element doesn't exist.
        assertTrue(closure.contains("TotallyUnknownInterface"))
      },
    ),
    suite("structuralType (platform-neutral type mapper)")(
      test("primitives map to plain Scala types, not js.* facade types") {
        val idl = Webref.Idl(Map.empty)
        assertTrue(
          DefBuilder.structuralType("DOMString", Set.empty, idl) == "String",
          DefBuilder.structuralType("boolean", Set.empty, idl) == "Boolean",
          DefBuilder.structuralType("long", Set.empty, idl) == "Int",
          DefBuilder.structuralType("unsigned long", Set.empty, idl) == "Int",
          DefBuilder.structuralType("double", Set.empty, idl) == "Double",
          DefBuilder.structuralType("unrestricted double", Set.empty, idl) == "Double",
          DefBuilder.structuralType("undefined", Set.empty, idl) == "Unit",
        )
      },
      test("a type name inside the in-scope closure resolves to its own generated trait name") {
        val idl     = Webref.Idl(Map.empty)
        val inScope = Set("HTMLInputElement", "NodeList")
        assertTrue(
          DefBuilder.structuralType("HTMLInputElement", inScope, idl) == "HTMLInputElement",
          DefBuilder.structuralType("NodeList", inScope, idl) == "NodeList",
        )
      },
      test("anything NOT in the in-scope closure falls back to the PlatformOpaque escape hatch") {
        // Rendering-only APIs (Canvas/WebGL) stay JS-only opt-in, outside the portable structural
        // surface — the mapper routes them through PlatformOpaque rather than a dangling reference.
        val idl     = Webref.Idl(Map.empty)
        val inScope = Set("HTMLInputElement")
        assertTrue(
          DefBuilder.structuralType("CanvasRenderingContext2D", inScope, idl) == "ascent.domcore.PlatformOpaque",
          DefBuilder.structuralType("WebGLRenderingContext", inScope, idl) == "ascent.domcore.PlatformOpaque",
          DefBuilder.structuralType("SomeUnmodelledUnion", inScope, idl) == "ascent.domcore.PlatformOpaque",
        )
      },
      test("a known IDL enum resolves to its real generated enum type in dom-types, not String") {
        // dom-core's backends do a genuine string<->enum conversion at the boundary, so the trait can
        // declare the real Scala 3 enum type instead of a String stand-in (unlike the @js.native path).
        val idl     = Webref.Idl(Map.empty, enums = List(Webref.IdlEnum("ShadowRootMode", List("open", "closed"))))
        val inScope = Set.empty[String]
        assertTrue(DefBuilder.structuralType("ShadowRootMode", inScope, idl) == "ascent.domtypes.ShadowRootMode")
      },
      test("an in-scope name takes priority over the primitive table even on a name collision") {
        // Pins the resolution order as in-scope-first, not primitive-first.
        val idl     = Webref.Idl(Map.empty)
        val inScope = Set("boolean")
        assertTrue(DefBuilder.structuralType("boolean", inScope, idl) == "boolean")
      },
      test("a known callback resolves to a plain Scala function type, never js.FunctionN") {
        // The callback's arg type recurses through structuralType too, so an in-scope arg interface
        // (Event) resolves to its own trait name rather than the opaque fallback.
        val idl = Webref.Idl(
          Map("Event" -> Webref.IdlInterface("Event", None, Nil)),
          callbacks =
            List(Webref.IdlCallback("EventListener", "undefined", List(Webref.IdlParam("event", "Event", false)))),
        )
        val inScope = Set("Event")
        assertTrue(DefBuilder.structuralType("EventListener", inScope, idl) == "Event => Unit")
      },
      test("a zero-arg callback resolves to `() => Ret`") {
        val idl = Webref.Idl(Map.empty, callbacks = List(Webref.IdlCallback("VoidCallback", "boolean", Nil)))
        assertTrue(DefBuilder.structuralType("VoidCallback", Set.empty, idl) == "() => Boolean")
      },
      test("a multi-arg callback resolves to a parenthesized tuple-arg function type") {
        val idl = Webref.Idl(
          Map.empty,
          callbacks = List(
            Webref.IdlCallback(
              "TwoArgCallback",
              "undefined",
              List(Webref.IdlParam("a", "DOMString", false), Webref.IdlParam("b", "boolean", false)),
            )
          ),
        )
        assertTrue(DefBuilder.structuralType("TwoArgCallback", Set.empty, idl) == "(String, Boolean) => Unit")
      },
      test("a callback param typed as an OUT-of-scope interface falls back to PlatformOpaque for that param") {
        val idl = Webref.Idl(
          Map.empty,
          callbacks = List(Webref.IdlCallback("Cb", "undefined", List(Webref.IdlParam("x", "SomeUnknownIface", false)))),
        )
        assertTrue(DefBuilder.structuralType("Cb", Set.empty, idl) == "ascent.domcore.PlatformOpaque => Unit")
      },
      test("a union idlType string resolves to a REAL Scala 3 union type, each member resolved independently") {
        // e.g. Element.setAttribute's "TrustedType | DOMString": TrustedType is out of scope (→
        // PlatformOpaque) while DOMString resolves normally, so the caller can still pass a plain String.
        val idl = Webref.Idl(Map.empty)
        assertTrue(
          DefBuilder.structuralType("TrustedType | DOMString", Set.empty, idl)
            == "ascent.domcore.PlatformOpaque | String"
        )
      },
      test("a union member that's IN SCOPE resolves to its own trait name inside the union") {
        val idl     = Webref.Idl(Map.empty)
        val inScope = Set("AbortSignal")
        assertTrue(
          DefBuilder.structuralType("AbortSignal | boolean", inScope, idl) == "AbortSignal | Boolean"
        )
      },
      test("a three-member union resolves and preserves declaration order") {
        val idl = Webref.Idl(Map.empty)
        assertTrue(
          DefBuilder.structuralType("DOMString | long | boolean", Set.empty, idl) == "String | Int | Boolean"
        )
      },
      test("a union whose members resolve to the SAME Scala type dedupes to a single member, not a repeated union") {
        val idl = Webref.Idl(Map.empty)
        assertTrue(
          DefBuilder.structuralType("SomeUnknownA | SomeUnknownB", Set.empty, idl) == "ascent.domcore.PlatformOpaque"
        )
      },
      test("a callback parameter typed as a union recurses through the SAME union-splitting logic") {
        val idl = Webref.Idl(
          Map.empty,
          callbacks =
            List(Webref.IdlCallback("Cb", "undefined", List(Webref.IdlParam("x", "DOMString | boolean", false)))),
        )
        assertTrue(DefBuilder.structuralType("Cb", Set.empty, idl) == "(String | Boolean) => Unit")
      },
      test("a sequence<T> idlType string resolves to a REAL List[T], the element type resolved independently") {
        val idl = Webref.Idl(Map.empty)
        assertTrue(DefBuilder.structuralType("sequence<DOMString>", Set.empty, idl) == "List[String]")
      },
      test("a sequence<T> whose element type is an in-scope interface resolves to List[<that trait>]") {
        val idl     = Webref.Idl(Map.empty)
        val inScope = Set("Node")
        assertTrue(DefBuilder.structuralType("sequence<Node>", inScope, idl) == "List[Node]")
      },
      test("a sequence<T> whose element type is unmodeled resolves to List[PlatformOpaque], not PlatformOpaque alone") {
        val idl = Webref.Idl(Map.empty)
        assertTrue(
          DefBuilder.structuralType("sequence<SomeUnknownIface>", Set.empty, idl)
            == "List[ascent.domcore.PlatformOpaque]"
        )
      },
    ),
  )
end DefBuilderSpec
