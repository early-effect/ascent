package ascent.domgen

import zio.test.*

object RendererSpec extends ZIOSpecDefault:

  private val elements = List(
    ElementDef(scalaName = "div", domName = "div", isVoid = false, interface = "HTMLDivElement"),
    ElementDef(scalaName = "input", domName = "input", isVoid = true, interface = "HTMLInputElement"),
    ElementDef(scalaName = "br", domName = "br", isVoid = true, interface = "HTMLBRElement"),
  )

  private val attrs = List(
    AttrDef(scalaName = "className", domName = "className", codec = CodecRef.StringAsIs),
    AttrDef(scalaName = "tabIndex", domName = "tabIndex", codec = CodecRef.IntAsString),
    AttrDef(scalaName = "required", domName = "required", codec = CodecRef.BooleanAsAttrPresence),
    // `type` is a hard Scala keyword: it emits backticked AND must offer a plain alias `typ`.
    AttrDef(scalaName = "type", domName = "type", codec = CodecRef.StringAsIs),
  )

  private val events = List(
    EventDef("onClick", "click", "ascent.dom.PointerEvent", bubbles = true),
    EventDef("onKeyDown", "keydown", "ascent.dom.KeyboardEvent", bubbles = true),
    EventDef("onAbort", "abort", "ascent.dom.Event", bubbles = false),
  )

  private val facades = List(
    FacadeDef(
      "Event",
      parent = None,
      members = List(FacadeMember("type", "String"), FacadeMember("bubbles", "Boolean")),
    ),
    FacadeDef("UIEvent", parent = Some("Event"), members = List(FacadeMember("detail", "Int"))),
    FacadeDef(
      "MouseEvent",
      parent = Some("UIEvent"),
      members = List(FacadeMember("clientX", "Double"), FacadeMember("clientY", "Double")),
    ),
  )

  private val enums = List(
    EnumDef("ShadowRootMode", List("open" -> "open", "closed" -> "closed")),
    EnumDef("EndingType", List("transparent" -> "transparent", "veryBad" -> "very-bad")),
  )

  def spec = suite("Renderer")(
    suite("dom-types: Elements.scala")(
      test("renders the canonical package + import header") {
        val src = Renderer.elements(elements)
        assertTrue(
          src.contains("package ascent.domtypes"),
          src.contains("// AUTO-GENERATED"),
        )
      },
      test("non-void elements emit ElementKey; void elements emit VoidElementKey") {
        val src = Renderer.elements(elements)
        assertTrue(
          src.contains("""val div: ElementKey = ElementKey("div")"""),
          // void elements get a distinct key type so the DSL can reject children at compile time
          src.contains("""val input: VoidElementKey = VoidElementKey("input")"""),
          src.contains("""val br: VoidElementKey = VoidElementKey("br")"""),
        )
      },
      test("groups all element vals inside object Elements") {
        val src = Renderer.elements(elements)
        assertTrue(src.contains("object Elements:"))
      },
    ),
    suite("dom-types: Attrs.scala")(
      test("emits an AttrKey[V] per attribute, parameterized by the codec's scala value type") {
        val src = Renderer.attrs(attrs)
        assertTrue(
          src.contains("""val className: AttrKey[String] = AttrKey("className", Codec.StringAsIs)"""),
          src.contains("""val tabIndex: AttrKey[Int] = AttrKey("tabIndex", Codec.IntAsString)"""),
          src.contains("""val required: AttrKey[Boolean] = AttrKey("required", Codec.BooleanAsAttrPresence)"""),
        )
      },
      test("groups all attr vals inside object Attrs") {
        val src = Renderer.attrs(attrs)
        assertTrue(src.contains("object Attrs:"))
      },
      test("a keyword-named attr emits backticked AND a plain-identifier alias pointing at it") {
        val src = Renderer.attrs(attrs)
        assertTrue(
          src.contains("""val `type`: AttrKey[String] = AttrKey("type", Codec.StringAsIs)"""),
          // ergonomic alias so authors can write `A.typ` instead of A.`type`
          src.contains("""val typ: AttrKey[String] = `type`"""),
        )
      },
    ),
    suite("dom-types: Events.scala (platform-neutral keys)")(
      test("emits an EventKey per event with the dom name and the facade type STRING (cross-platform)") {
        val src = Renderer.events(events)
        assertTrue(
          src.contains("""val onClick: EventKey = EventKey("click", "ascent.dom.PointerEvent")"""),
          src.contains("""val onKeyDown: EventKey = EventKey("keydown", "ascent.dom.KeyboardEvent")"""),
        )
      },
      test("groups all event vals inside object Events") {
        val src = Renderer.events(events)
        assertTrue(src.contains("object Events:"))
      },
    ),
    suite("js: TypedEvents.scala (typed event handler DSL)")(
      test("emits a typed handler factory per event, casting AscentEvent.raw to the spec interface") {
        val src = Renderer.typedEvents(events)
        assertTrue(
          src.contains("package ascent.js"),
          src.contains("import ascent.ast.Attr"),
          // No bare `import ascent.dom`: dom refs are fully qualified, so the import would trip `-Wunused`.
          !src.contains("\nimport ascent.dom\n"),
          src.contains("def onClick[R](handler: ascent.dom.PointerEvent => zio.URIO[R, Unit]): Attr[R]"),
          src.contains("def onKeyDown[R](handler: ascent.dom.KeyboardEvent => zio.URIO[R, Unit]): Attr[R]"),
          src.contains("Attr.EventHandler(\"click\", e => handler(e.raw.asInstanceOf[ascent.dom.PointerEvent]))"),
        )
      },
      test("the file is wrapped in `object TypedEvents` so users can import * cleanly") {
        val src = Renderer.typedEvents(events)
        assertTrue(src.contains("object TypedEvents:"))
      },
      test("emits a nested `object sync` with side-effecting (T => Unit) factories lifted via ZIO.attempt") {
        // `.attempt(...).orDie`, not `succeed`: DOM blocks throw in practice, so attempt captures the
        // throw and the mount logs the failed exit rather than dropping it silently.
        val src = Renderer.typedEvents(events)
        assertTrue(
          src.contains("object sync:"),
          // sync handlers need no environment, so they return Attr[Any].
          src.contains("def onClick(handler: ascent.dom.PointerEvent => Unit): Attr[Any]"),
          src.contains(
            """Attr.EventHandler("click", e => zio.ZIO.attempt(handler(e.raw.asInstanceOf[ascent.dom.PointerEvent])).orDie)"""
          ),
          src.contains("def onKeyDown(handler: ascent.dom.KeyboardEvent => Unit): Attr[Any]"),
        )
      },
    ),
    suite("dom-facade: Facades.scala (@js.native event hierarchy)")(
      test("emits an @js.native @JSGlobal facade per interface, extending its parent when present") {
        val src = Renderer.facades(facades)
        assertTrue(
          src.contains("@js.native"),
          src.contains("@JSGlobal"),
          src.contains("class Event extends js.Object"),
          src.contains("class UIEvent extends Event"),
          src.contains("class MouseEvent extends UIEvent"),
        )
      },
      test("declares each member as an @js.native def with the mapped scala type") {
        val src = Renderer.facades(facades)
        assertTrue(
          src.contains("def clientX: Double"),
          src.contains("def clientY: Double"),
          src.contains("def detail: Int"),
          src.contains("def `type`: String"),
          src.contains("def bubbles: Boolean"),
        )
      },
      test("lives in the ascent.dom package so generated event keys' string types resolve") {
        val src = Renderer.facades(facades)
        assertTrue(src.contains("package ascent.dom"))
      },
    ),
    suite("safety: identifier escaping")(
      test("a scalaName that collides with a Scala keyword is backticked in the emitted val") {
        val keywordAttr = List(
          AttrDef(scalaName = "type", domName = "type", codec = CodecRef.StringAsIs)
        )
        val src = Renderer.attrs(keywordAttr)
        assertTrue(src.contains("val `type`: AttrKey[String]"))
      }
    ),
    suite("dom-types: Enums.scala (real Scala 3 enums, platform-neutral)")(
      test("emits a real `enum` per WebIDL enum, with each case carrying its dom literal via domValue") {
        val src = Renderer.enumTypes(enums)
        assertTrue(
          src.contains("package ascent.domtypes"),
          src.contains("enum ShadowRootMode(val domValue: String)"),
          src.contains("case Open extends ShadowRootMode(\"open\")"),
          src.contains("case Closed extends ShadowRootMode(\"closed\")"),
        )
      },
      test("hyphenated/multi-word dom values get a camel-cased case name but keep the raw domValue") {
        val src = Renderer.enumTypes(enums)
        assertTrue(
          src.contains("case VeryBad extends EndingType(\"very-bad\")"),
          src.contains("case Transparent extends EndingType(\"transparent\")"),
        )
      },
      test("emits a fromDom companion lookup so callers can parse an untrusted wire string safely") {
        val src = Renderer.enumTypes(enums)
        assertTrue(
          src.contains("def fromDom(domValue: String): Option[ShadowRootMode]"),
          src.contains("values.find(_.domValue == domValue)"),
        )
      },
      test("has zero scalajs / js.* references — the file compiles identically on jvm/js/native") {
        val src = Renderer.enumTypes(enums)
        assertTrue(!src.contains("js."), !src.contains("scalajs"))
      },
      test("no scalajs dependency: platform-neutral, unlike Facades.scala") {
        val src = Renderer.enumTypes(enums)
        assertTrue(!src.contains("@js.native"), !src.contains("@JSGlobal"))
      },
    ),
    suite("dom-facade: enum-typed accessors (additive, bridging @js.native String members to real enums)")(
      test("emits a `<name>Typed` extension getter that parses the native String member via fromDom") {
        val facadesWithEnum = List(
          FacadeDef(
            "ShadowRootInit",
            parent = None,
            members = List(FacadeMember("mode", "String", enumType = Some("ShadowRootMode"))),
          )
        )
        val src = Renderer.enumAccessors(Nil, facadesWithEnum)
        assertTrue(
          src.contains("extension (self: ShadowRootInit)"),
          src.contains("def modeTyped: Option[ascent.domtypes.ShadowRootMode]"),
          src.contains("ascent.domtypes.ShadowRootMode.fromDom(self.mode)"),
        )
      },
      test("members without an enumType are skipped entirely — no accessor emitted") {
        val facadesNoEnum = List(
          FacadeDef("Plain", parent = None, members = List(FacadeMember("clientX", "Double")))
        )
        val src = Renderer.enumAccessors(Nil, facadesNoEnum)
        assertTrue(!src.contains("clientXTyped"))
      },
      test("does not redeclare the native member's own type — String stays String on the js.native class") {
        // The accessor is a separate extension; it must not restate `mode: String`, since js.native
        // erasure requires the native member's type to match the raw wire value in Facades.scala.
        val facadesWithEnum = List(
          FacadeDef(
            "ShadowRootInit",
            parent = None,
            members = List(FacadeMember("mode", "String", enumType = Some("ShadowRootMode"))),
          )
        )
        val src = Renderer.enumAccessors(Nil, facadesWithEnum)
        assertTrue(!src.contains("def mode: "), !src.contains("var mode:"))
      },
      test("an interface with no enum-typed members emits no extension block for it at all") {
        val facadesNoEnum = List(
          FacadeDef("Plain", parent = None, members = List(FacadeMember("clientX", "Double")))
        )
        val src = Renderer.enumAccessors(Nil, facadesNoEnum)
        assertTrue(!src.contains("extension (self: Plain)"))
      },
      test("an interface (Interfaces.scala) with an enum-typed member also gets an accessor") {
        val ifaceDefs = List(
          InterfaceDef(
            "HTMLMediaElement",
            parent = None,
            attributes = List(FacadeMember("crossOrigin", "String", enumType = Some("CrossOriginType"))),
            methods = Nil,
          )
        )
        val src = Renderer.enumAccessors(ifaceDefs, Nil)
        assertTrue(
          src.contains("extension (self: HTMLMediaElement)"),
          src.contains("def crossOriginTyped: Option[ascent.domtypes.CrossOriginType]"),
        )
      },
      test("a keyword-named enum-typed member (e.g. `type`) backticks the COMBINED accessor identifier") {
        // `safeId` must wrap the whole combined name ("typeTyped"); backticking just the base
        // (`` `type` `` + `Typed`) yields two tokens and a syntax error.
        val ifaceDefs = List(
          InterfaceDef(
            "RTCIceCandidate",
            parent = None,
            attributes = List(FacadeMember("type", "String", enumType = Some("RTCIceCandidateType"))),
            methods = Nil,
          )
        )
        val src = Renderer.enumAccessors(ifaceDefs, Nil)
        assertTrue(
          src.contains("def typeTyped: Option[ascent.domtypes.RTCIceCandidateType]"),
          src.contains("self.`type`"),
          !src.contains("`type`Typed"),
        )
      },
    ),
    suite("dom-core: structuralTraits (platform-neutral element/core interface catalog)")(
      test("emits a plain abstract trait per interface — no js.native, no JSGlobal, no scalajs import") {
        val defs = List(
          InterfaceDef("HTMLAnchorElement", parent = Some("HTMLElement"), attributes = Nil, methods = Nil)
        )
        val src = Renderer.structuralTraits(defs)
        assertTrue(
          src.contains("package ascent.domcore.generated"),
          src.contains("trait HTMLAnchorElement extends HTMLElement"),
          !src.contains("js.native"),
          !src.contains("JSGlobal"),
          !src.contains("scalajs"),
        )
      },
      test("a readonly attribute emits a bare getter; a writable one emits getter + setter pair") {
        val defs = List(
          InterfaceDef(
            "HTMLAnchorElement",
            parent = None,
            attributes = List(
              FacadeMember("href", "String", readonly = false),
              FacadeMember("origin", "String", readonly = true),
            ),
            methods = Nil,
          )
        )
        val src = Renderer.structuralTraits(defs)
        assertTrue(
          src.contains("def href: String"),
          src.contains("def href_=(value: String): Unit"),
          src.contains("def origin: String"),
          !src.contains("def origin_=("),
        )
      },
      test("methods render as abstract defs with their mapped params/return type") {
        val defs = List(
          InterfaceDef(
            "HTMLFormElement",
            parent = None,
            attributes = Nil,
            methods = List(MethodDef("submit", "submit", "Unit", Nil)),
          )
        )
        val src = Renderer.structuralTraits(defs)
        assertTrue(src.contains("def submit(): Unit"))
      },
      test("an interface with no parent falls back to no explicit superclass (bare trait declaration)") {
        val defs = List(InterfaceDef("Node", parent = None, attributes = Nil, methods = Nil))
        val src  = Renderer.structuralTraits(defs)
        assertTrue(src.contains("trait Node"), !src.contains("trait Node extends"))
      },
      test("a keyword-named member (e.g. `type`) is backticked, matching Renderer's existing keyword-safety") {
        val defs = List(
          InterfaceDef(
            "HTMLButtonElement",
            parent = None,
            attributes = List(FacadeMember("type", "String", readonly = false)),
            methods = Nil,
          )
        )
        val src = Renderer.structuralTraits(defs)
        // safeId checks the combined identifier "type_=" (not a keyword) so the setter renders bare as
        // `def type_=(...)`; the broken form guarded against is `` def `type`_=(...) ``, a dangling `_=`.
        assertTrue(src.contains("def `type`: String"), src.contains("def type_=(value: String): Unit"))
      },
    ),
    suite("dom-core: memoryImpls (in-memory backend, reflection-driven)")(
      test(
        "imports the parent ascent.domcore package so the hand-written kernel (NodeMemoryBase / *Overrides) resolves"
      ) {
        // The kernel lives in the PARENT `ascent.domcore` package; this file is `ascent.domcore.generated`,
        // and Scala 3 doesn't expose a parent package's members to a subpackage without an explicit import.
        val defs = List(InterfaceDef("EventTarget", parent = None, attributes = Nil, methods = Nil))
        val src  = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(src.contains("import ascent.domcore.*"))
      },
      test("a reflected attribute auto-implements as attributeMap.getOrElse/attributeMap.set — zero hand code") {
        val defs = List(
          InterfaceDef(
            "HTMLAnchorElement",
            parent = Some("HTMLElement"),
            attributes = List(
              FacadeMember("href", "String", readonly = false, reflected = true, reflectedAttrName = Some("href"))
            ),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("class HTMLAnchorElementMemory extends HTMLElementMemory with HTMLAnchorElement"),
          src.contains("""attributeMap.getOrElse("href", "")"""),
          src.contains("""attributeMap.set("href", value)"""),
        )
      },
      test(
        "a [Reflect]-marked attribute typed as a NON-codec type (e.g. DOMTokenList) falls through to ???, not attributeMap"
      ) {
        // attributeMap.getOrElse/set need an AttrCodec[V], which only exists for String/Boolean/Int/Double.
        // A reflected attr typed as DOMTokenList (real: HTMLLinkElement.relList) must skip attributeMap or
        // it fails to compile ("No given instance of AttrCodec[DOMTokenList]"), falling through to ???.
        val defs = List(
          InterfaceDef(
            "HTMLLinkElement",
            parent = Some("HTMLElement"),
            attributes = List(
              FacadeMember(
                "relList",
                "DOMTokenList",
                readonly = true,
                reflected = true,
                reflectedAttrName = Some("rel"),
              )
            ),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("def relList: DOMTokenList = ???"),
          !src.contains("attributeMap.getOrElse(\"rel\""),
        )
      },
      test("a non-reflected WRITABLE primitive attribute falls back to a generic mutable field") {
        val defs = List(
          InterfaceDef(
            "HTMLInputElement",
            parent = None,
            attributes = List(FacadeMember("defaultValue", "String", readonly = false, reflected = false)),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("private var _defaultValue: String"),
          src.contains("def defaultValue: String = _defaultValue"),
          src.contains("def defaultValue_=(value: String): Unit = _defaultValue = value"),
        )
      },
      test("a non-reflected READONLY primitive attribute returns the zero value directly — no unused mutable field") {
        // A readonly attribute is never assigned, so a backing `private var` would trip Scala 3's E198
        // "unset private variable" warning under -Wunused:all — the getter returns the zero value instead.
        val defs = List(
          InterfaceDef(
            "Element",
            parent = None,
            attributes = List(FacadeMember("clientWidth", "Int", readonly = true, reflected = false)),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("def clientWidth: Int = 0"),
          !src.contains("_clientWidth"), // no backing field
          !src.contains("clientWidth_="), // no setter (it's readonly)
        )
      },
      test("a member on the handWrittenOverrides list is skipped entirely — generator emits nothing for it") {
        val defs = List(
          InterfaceDef(
            "HTMLInputElement",
            parent = None,
            attributes = List(FacadeMember("checked", "Boolean", readonly = false, reflected = false)),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set("HTMLInputElement" -> "checked"))
        assertTrue(
          !src.contains("_checked"),
          !src.contains("def checked"),
          src.contains("with HTMLInputElementOverrides"), // the override trait supplies it instead
        )
      },
      test("zero-value defaults are type-appropriate: empty string / false / 0 / unit, not null") {
        val defs = List(
          InterfaceDef(
            "X",
            parent = None,
            attributes = List(
              FacadeMember("s", "String", readonly = false, reflected = false),
              FacadeMember("b", "Boolean", readonly = false, reflected = false),
              FacadeMember("i", "Int", readonly = false, reflected = false),
              FacadeMember("d", "Double", readonly = false, reflected = false),
            ),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("""private var _s: String = """"),
          src.contains("private var _b: Boolean = false"),
          src.contains("private var _i: Int = 0"),
          src.contains("private var _d: Double = 0.0"),
        )
      },
      test("an interface with a parent extends that parent's Memory class") {
        val defs = List(
          InterfaceDef("HTMLAnchorElement", parent = Some("HTMLElement"), attributes = Nil, methods = Nil)
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(src.contains("class HTMLAnchorElementMemory extends HTMLElementMemory with HTMLAnchorElement"))
      },
      test("EventTarget specifically — the TRUE root of the DOM tree — extends the shared NodeMemoryBase kernel") {
        val defs = List(InterfaceDef("EventTarget", parent = None, attributes = Nil, methods = Nil))
        val src  = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(src.contains("class EventTargetMemory extends NodeMemoryBase with EventTarget"))
      },
      test("a parentless interface that is NOT EventTarget gets no forced superclass at all") {
        // Event has no IDL parent but is a sibling of Node under EventTarget, not a descendant.
        // Forcing every parentless interface to extend NodeMemoryBase would wrongly give Event
        // Node-tree members (appendChild, parentNode, ...); same for orphans like NodeList.
        val defs = List(InterfaceDef("Event", parent = None, attributes = Nil, methods = Nil))
        val src  = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        // Match the class declaration line specifically: the file header mentions NodeMemoryBase, so a
        // bare `!src.contains(...)` would false-fail on that comment instead of the `extends` clause.
        val eventClassLine = src.linesIterator.find(_.startsWith("class EventMemory")).getOrElse("")
        assertTrue(
          eventClassLine == "class EventMemory extends Event",
          !eventClassLine.contains("NodeMemoryBase"),
        )
      },
      test("a PlatformOpaque-typed member has no meaningful zero value — implements the def directly as ???") {
        // A member outside the portable structural surface has no honest zero value, so it implements
        // the abstract def as `???` (compiles, throws only if called) rather than a fabricated placeholder.
        val defs = List(
          InterfaceDef(
            "HTMLCanvasElement",
            parent = None,
            attributes = List(
              FacadeMember("unused", "ascent.domcore.PlatformOpaque", readonly = false, reflected = false)
            ),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("def unused: ascent.domcore.PlatformOpaque = ???"),
          src.contains("def unused_=(value: ascent.domcore.PlatformOpaque): Unit = ???"),
          !src.contains("_unused"), // no backing field at all
        )
      },
      test("a readonly PlatformOpaque-typed member gets only the getter as ???, no setter") {
        val defs = List(
          InterfaceDef(
            "HTMLCanvasElement",
            parent = None,
            attributes = List(
              FacadeMember("readOnlyOpaque", "ascent.domcore.PlatformOpaque", readonly = true, reflected = false)
            ),
            methods = Nil,
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(
          src.contains("def readOnlyOpaque: ascent.domcore.PlatformOpaque = ???"),
          !src.contains("readOnlyOpaque_="),
        )
      },
      test("a method with no hand-written override implements the trait's abstract def as ??? — pervasive, not rare") {
        // Methods have no "zero value" story (behavior, not stored state), so every generated method
        // defaults to ??? unless hand-overridden.
        val defs = List(
          InterfaceDef(
            "HTMLFormElement",
            parent = None,
            attributes = Nil,
            methods = List(MethodDef("submit", "submit", "Unit", Nil)),
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set.empty)
        assertTrue(src.contains("def submit(): Unit = ???"))
      },
      test("a method named in handWrittenOverrides is skipped entirely — the override trait supplies it") {
        val defs = List(
          InterfaceDef(
            "HTMLMediaElement",
            parent = None,
            attributes = Nil,
            methods = List(MethodDef("play", "play", "Unit", Nil)),
          )
        )
        val src = Renderer.memoryImpls(defs, handWrittenOverrides = Set("HTMLMediaElement" -> "play"))
        assertTrue(
          !src.contains("def play"),
          src.contains("with HTMLMediaElementOverrides"),
        )
      },
    ),
    suite("dom-core: elementFactory (tag-name -> Memory-class dispatch table)")(
      test("emits one case per element, dispatching to the interface's Memory class constructor") {
        val src = Renderer.elementFactory(elements, fallbackInterface = "HTMLUnknownElement")
        assertTrue(
          src.contains("""case "div" => HTMLDivElementMemory()"""),
          src.contains("""case "input" => HTMLInputElementMemory()"""),
          src.contains("""case "br" => HTMLBRElementMemory()"""),
        )
      },
      test("an unrecognized tag name falls back to the given fallback interface's Memory class") {
        val src = Renderer.elementFactory(elements, fallbackInterface = "HTMLUnknownElement")
        assertTrue(src.contains("""case _ => HTMLUnknownElementMemory()"""))
      },
      test(
        "two elements sharing one interface (e.g. multiple obsolete tags -> HTMLUnknownElement) both dispatch to it"
      ) {
        val shared = List(
          ElementDef(scalaName = "applet", domName = "applet", isVoid = false, interface = "HTMLUnknownElement"),
          ElementDef(scalaName = "bgsound", domName = "bgsound", isVoid = false, interface = "HTMLUnknownElement"),
        )
        val src = Renderer.elementFactory(shared, fallbackInterface = "HTMLUnknownElement")
        assertTrue(
          src.contains("""case "applet" => HTMLUnknownElementMemory()"""),
          src.contains("""case "bgsound" => HTMLUnknownElementMemory()"""),
        )
      },
      test("compiles with zero js.native / scalajs references — jvm/js/native platform-neutral") {
        val src = Renderer.elementFactory(elements, fallbackInterface = "HTMLUnknownElement")
        assertTrue(!src.contains("js.native"), !src.contains("scalajs"), !src.contains("@JSGlobal"))
      },
      test(
        "a tag name shared by HTML and SVG (e.g. `a`, `title`) emits ONE case, first-wins — no unreachable duplicate"
      ) {
        // HTML and SVG both define `a`/`title`/`script`/etc. The generator lists HTML first (createElement
        // is HTML-namespaced), so it wins; a second `case "a"` would be an unreachable clause (E030).
        val htmlThenSvg = List(
          ElementDef(scalaName = "a", domName = "a", isVoid = false, interface = "HTMLAnchorElement"),
          ElementDef(scalaName = "a", domName = "a", isVoid = false, interface = "SVGAElement"),
        )
        val src        = Renderer.elementFactory(htmlThenSvg, fallbackInterface = "HTMLUnknownElement")
        val aCaseLines = src.linesIterator.filter(_.contains("""case "a" =>""")).toList
        assertTrue(
          aCaseLines.size == 1,                                // exactly one case for the shared tag
          aCaseLines.head.contains("HTMLAnchorElementMemory"), // HTML (listed first) won
          !src.contains("SVGAElementMemory"),                  // the SVG duplicate was dropped
        )
      },
    ),
  )
end RendererSpec
