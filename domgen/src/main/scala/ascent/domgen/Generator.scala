package ascent.domgen

import zio.*

/** Pure inputs to the generator, fully assembled by the caller (parsed JSON + curated config).
  *
  * Keeping this a plain value lets tests build small, focused inputs without I/O — the file-loading step is a separate
  * effect ([[Generator.loadVendored]]) layered on top of the same pipeline.
  */
final case class GeneratorInput(
    elements: List[Webref.Element],
    events: List[Webref.Event],
    idl: Webref.Idl,
    /** Only events whose `type` is in this set become handler keys. webref's events.json carries ~700 entries spanning
      * many esoteric specs; the allowlist keeps the generated surface focused on what real ascent applications use.
      */
    eventAllowlist: Set[String],
    /** HTML elements that get a per-element typed attribute set (walked through IDL inheritance). Everything else uses
      * the global+common attr surface only.
      */
    strictElements: Set[String],
    /** SVG elements, from `elements/{SVG2,svg-animations,svg-paths}.json`. Feeds the structural DOM catalog's element
      * closure alongside `elements` (HTML) — the two together are the FULL set of `createElement`-reachable tags the
      * cross-platform DOM catalog covers. Defaults to empty so every existing caller (built before SVG was vendored)
      * stays behavior-unchanged.
      */
    svgElements: List[Webref.Element] = Nil,
)

/** Output of one generator run: a map from logical relative-path → Scala source contents.
  *
  * Logical paths are stable identifiers like `"dom-types/Elements.scala"`. The I/O wrapper writes them to concrete
  * locations under the project tree; tests inspect them in-memory.
  */
final case class GeneratorOutput(files: Map[String, String])

/** Typed errors the generator can surface. Using a sealed hierarchy means callers (and tests) pattern-match instead of
  * stringly comparing — and `Generator.run` declares the failure shape in its type so missing IDL never silently emits
  * broken code.
  */
sealed trait GeneratorError extends Product with Serializable
object GeneratorError:
  /** An event references an interface that isn't in the merged IDL; the facade can't be emitted because we don't know
    * its members or parent. The plan calls for failing loudly here.
    */
  final case class UnknownEventInterface(interface: String, eventType: String) extends GeneratorError

object Generator:

  /** Pure pipeline: parsed inputs → rendered Scala source files. No I/O. */
  def run(input: GeneratorInput): IO[GeneratorError, GeneratorOutput] =
    for
      // Filter events through the allowlist, then collapse to one entry per event type by picking
      // the most-specific interface in the IDL inheritance chain. webref carries multiple entries
      // for the same type (e.g. `abort` on Event vs ProgressEvent for media); the typed handler
      // DSL needs exactly one canonical interface per event so the emitted val name is unique.
      filteredEvents <- ZIO.succeed(
        pickMostSpecificPerType(
          input.events.filter(e => input.eventAllowlist.contains(e.`type`)),
          input.idl,
        )
      )
      // Validate every event interface is resolvable from the IDL before rendering.
      _ <- ZIO.foreachDiscard(filteredEvents): e =>
        if input.idl.interfaces.contains(e.interface) then ZIO.unit
        else ZIO.fail(GeneratorError.UnknownEventInterface(e.interface, e.`type`))
      // Build the def model.
      elementDefs = DefBuilder.elementDefs(input.elements)
      attrDefs    = strictAttrDefs(input.elements, input.strictElements, input.idl)
      eventDefs   = DefBuilder.eventDefs(filteredEvents)
      facadeDefs  = DefBuilder.facadeDefs(filteredEvents, input.idl)
      // Anything emitted as an event facade (Facades.scala) or as an engine-owned class
      // (EngineFacade.scala — Element, Document, Node, EventTarget, Text, Comment,
      // HTMLInputElement) must NOT also be emitted as an interface, or we'd get duplicate
      // class definitions in the same package.
      eventFacadeNames = facadeDefs.map(_.interface).toSet
      interfaceDefs    = DefBuilder.interfaceDefs(input.idl, skipNames = engineOwnedInterfaces ++ eventFacadeNames)
      dictionaryDefs   = DefBuilder.dictionaryDefs(input.idl)
      enumDefs         = DefBuilder.enumDefs(input.idl)
      // Platform-neutral structural DOM catalog (dom-core): every HTML + SVG element interface plus
      // its full ancestor chain plus the forced core set (Node/Element/Document/...), generated as
      // plain abstract traits (no js.native) via the SAME interfaceDefs walk, just parameterized on
      // structuralType instead of scalaFacadeType so the identical attribute/method collection logic
      // produces a platform-neutral shape.
      structuralClosure = DefBuilder.elementInterfaceClosure(input.elements, input.svgElements, input.idl)
      structuralDefs    = DefBuilder.interfaceDefs(
        input.idl,
        skipNames = input.idl.interfaces.keySet -- structuralClosure,
        typeOf = (idlType, idl) => DefBuilder.structuralType(idlType, structuralClosure, idl),
      )
    yield GeneratorOutput(
      Map(
        "dom-types/Elements.scala"       -> Renderer.elements(elementDefs),
        "dom-types/Attrs.scala"          -> Renderer.attrs(attrDefs),
        "dom-types/Events.scala"         -> Renderer.events(eventDefs),
        "dom-types/Enums.scala"          -> Renderer.enumTypes(enumDefs),
        "dom-facade/Facades.scala"       -> Renderer.facades(facadeDefs),
        "dom-facade/Interfaces.scala"    -> Renderer.interfaces(interfaceDefs),
        "dom-facade/Dictionaries.scala"  -> Renderer.dictionaries(dictionaryDefs),
        "dom-facade/EnumAccessors.scala" -> Renderer.enumAccessors(interfaceDefs, facadeDefs),
        "js/TypedEvents.scala"           -> Renderer.typedEvents(eventDefs),
        "dom-core/Elements.scala"        -> Renderer.structuralTraits(structuralDefs),
        "dom-core/ElementsMemory.scala"  -> Renderer.memoryImpls(
          structuralDefs.filterNot(d => noMemoryImplClass.contains(d.name)),
          handWrittenOverrides,
        ),
        // HTML + SVG together — cloneNode needs to reconstruct either kind found in the tree
        // (createElement itself is an HTML-only entry point per spec; SVG elements are created
        // via createElementNS, which isn't yet modeled — see DocumentOverrides).
        "dom-core/ElementFactory.scala" -> Renderer.elementFactory(
          elementDefs ++ DefBuilder.elementDefs(input.svgElements),
          fallbackInterface = "HTMLUnknownElement",
        ),
      )
    )

  /** Non-reflected members with genuinely custom behavior beyond what a generic mutable field or `???` stub can express
    * — a short, explicit, hand-audited list. The generator emits NOTHING for these; a hand-written
    * `<Interface>Overrides.scala` trait (checked into `dom-core`, NOT generated) supplies real behavior instead. Grows
    * only when a test demonstrates the generic default is observably wrong for real usage — never guessed at upfront.
    * See [[Renderer.memoryImpls]] for the full policy.
    */
  private val handWrittenOverrides: Set[(String, String)] = Set(
    // Attr.name is readonly per WebIDL, so the reflection generator emits a fixed `def name = ""` — a
    // synthesized Attr (NamedNodeMapView) then can't report which attribute it stands for, breaking the
    // getNamedItem round-trip. Backed by NodeMemoryBase.nodeNameRef (stamped by the view) instead.
    "Attr"                -> "name",
    "HTMLInputElement"    -> "value",
    "HTMLInputElement"    -> "checked",
    "HTMLInputElement"    -> "indeterminate",
    "HTMLInputElement"    -> "files",
    "HTMLInputElement"    -> "validity",
    "HTMLInputElement"    -> "valueAsNumber",
    "HTMLInputElement"    -> "valueAsDate",
    "HTMLSelectElement"   -> "selectedIndex",
    "HTMLSelectElement"   -> "options",
    "HTMLSelectElement"   -> "value",
    "HTMLSelectElement"   -> "selectedOptions",
    "HTMLTextAreaElement" -> "value",
    "HTMLOptionElement"   -> "selected",
    "HTMLOptionElement"   -> "index",
    // EventTarget's listener methods need real storage/dispatch behavior — a stored listener
    // list, actually invoking matching listeners on dispatchEvent — that no generic default
    // (a field, a `???` stub) can express.
    "EventTarget" -> "addEventListener",
    "EventTarget" -> "removeEventListener",
    "EventTarget" -> "dispatchEvent",
    // Event carries mutable DISPATCH STATE (target/currentTarget as dispatch walks the tree,
    // defaultPrevented + the propagation-stop flags) that dispatchEvent reads and writes — and
    // initEvent is its in-memory constructor. A generic field / ??? can't model the algorithm,
    // so EventOverrides supplies these against refs on the Event's own NodeMemoryBase-free state.
    "Event" -> "target",
    "Event" -> "currentTarget",
    "Event" -> "type",
    "Event" -> "bubbles",
    "Event" -> "cancelable",
    "Event" -> "defaultPrevented",
    "Event" -> "preventDefault",
    "Event" -> "stopPropagation",
    "Event" -> "stopImmediatePropagation",
    "Event" -> "initEvent",
    // Element's attribute/tree-query surface needs real behavior backed by the SAME attribute
    // map / child list the reflected-attribute accessors and Node's tree methods already use —
    // not a fabricated PlatformOpaque value. setAttribute/getAttributeNames'/children/attributes
    // reflect real state; querySelector*/matches/closest delegate to the css selector engine.
    "Element" -> "setAttribute",
    "Element" -> "setAttributeNS",
    "Element" -> "getAttribute",
    "Element" -> "getAttributeNS",
    "Element" -> "removeAttribute",
    "Element" -> "removeAttributeNS",
    "Element" -> "hasAttribute",
    "Element" -> "hasAttributeNS",
    "Element" -> "hasAttributes",
    "Element" -> "toggleAttribute",
    "Element" -> "getAttributeNames",
    "Element" -> "attributes",
    "Element" -> "children",
    "Element" -> "classList",
    "Element" -> "tagName",
    // id/className are `[Reflect]` per the DOM spec, but the vendored webref snapshot tags them only
    // `[CEReactions]` (no `[Reflect]`) — so the reflection generator would emit them as generic fields
    // disconnected from the attribute map, desyncing `el.id = x` from `getAttribute("id")` and breaking
    // getElementById / class selectors / serialization. Hand-written against the attribute map instead.
    "Element" -> "id",
    "Element" -> "className",
    // innerHTML/outerHTML are real serialization of the live subtree — a genuine DOM feature (also used by
    // the SSR path, which mounts into an in-memory tree then reads root.innerHTML). Hand-written in
    // ElementSerializationOverrides: walk children, escape text/attrs, void-element aware. The setters
    // (parse-and-replace) stay ??? — parsing HTML back into a tree isn't modeled.
    "Element" -> "innerHTML",
    "Element" -> "outerHTML",
    "Element" -> "querySelector",
    "Element" -> "querySelectorAll",
    "Element" -> "matches",
    "Element" -> "closest",
    "Element" -> "getElementsByTagName",
    "Element" -> "getElementsByClassName",
    // Document's creation/navigation surface is the whole point of an in-memory backend —
    // createElement dispatches to the right concrete *Memory class by tag name; the rest are
    // real tree queries over the SAME child structure Node already maintains.
    "Document" -> "createElement",
    "Document" -> "createTextNode",
    "Document" -> "createComment",
    "Document" -> "documentElement",
    "Document" -> "body",
    "Document" -> "body_=",
    "Document" -> "head",
    "Document" -> "activeElement",
    "Document" -> "getElementById",
    "Document" -> "querySelector",
    "Document" -> "querySelectorAll",
    // CharacterData's derived string-manipulation methods (substringData/appendData/insertData/
    // deleteData/replaceData) are all real behavior over the `data` field the generator already
    // backs with a plain var — small, mechanical, and worth implementing for real rather than
    // stubbing derived-but-well-defined string ops. `length` is the UTF-16 code-unit count of `data`
    // (a generic zero-value field would leave it a constant 0 — createTextNode("abc").length == 0).
    "CharacterData" -> "length",
    "CharacterData" -> "substringData",
    "CharacterData" -> "appendData",
    "CharacterData" -> "insertData",
    "CharacterData" -> "deleteData",
    "CharacterData" -> "replaceData",
    "CharacterData" -> "remove",
    // Node is THE tree kernel — every one of its members has a real, well-defined answer given
    // an actual parent/children structure (even the namespace-lookup trio: the correct output of
    // the real algorithm against a model with no namespace declarations is deterministically
    // null/false, which is what the hand-written implementation returns — not a stub standing in
    // for missing behavior). All of Node routes through the SAME parent/children storage
    // NodeMemoryBase owns, so it's overridden as one unit rather than split member-by-member.
    // nodeType/nodeName are stamped at construction (ElementFactory / Document.create*) into
    // NodeMemoryBase's nodeTypeRef/nodeNameRef and read back here — a generic `def = 0`/`""`
    // default (what the reflection generator would emit) leaves every element ignorant of its
    // own tag and kind, breaking tagName / getElementsByTagName / tag selectors / serialization.
    "Node" -> "nodeType",
    "Node" -> "nodeName",
    "Node" -> "parentNode",
    "Node" -> "parentElement",
    "Node" -> "childNodes",
    "Node" -> "firstChild",
    "Node" -> "lastChild",
    "Node" -> "previousSibling",
    "Node" -> "nextSibling",
    "Node" -> "ownerDocument",
    "Node" -> "isConnected",
    "Node" -> "nodeValue",
    "Node" -> "nodeValue_=",
    "Node" -> "textContent",
    "Node" -> "textContent_=",
    "Node" -> "getRootNode",
    "Node" -> "hasChildNodes",
    "Node" -> "normalize",
    "Node" -> "cloneNode",
    "Node" -> "isEqualNode",
    "Node" -> "isSameNode",
    "Node" -> "compareDocumentPosition",
    "Node" -> "contains",
    "Node" -> "lookupPrefix",
    "Node" -> "lookupNamespaceURI",
    "Node" -> "isDefaultNamespace",
    "Node" -> "insertBefore",
    "Node" -> "appendChild",
    "Node" -> "replaceChild",
    "Node" -> "removeChild",
  )

  /** Interfaces excluded from [[Renderer.memoryImpls]]'s CLASS generation entirely (their TRAIT still generates
    * normally into `Elements.scala` — other members reference them by trait name). These are live VIEWS over another
    * object's existing data (a node's children, an element's attribute map, an element's class-token list) — not
    * standalone creatable things — so they need a constructor parameter no generated no-arg `class XMemory` can
    * express. Hand-written directly in `dom-core` (`NodeListView`, `HTMLCollectionView`, `DOMTokenListView`,
    * `NamedNodeMapView`), constructed wherever a `Node`/`Element` override needs to hand one back (`Node.childNodes`,
    * `Element.children`, `Element.classList`, `Element.attributes`).
    */
  private val noMemoryImplClass: Set[String] = Set("NodeList", "HTMLCollection", "DOMTokenList", "NamedNodeMap")

  /** Interfaces hand-written in `EngineFacade.scala` — the binding engine references them directly, so they're the
    * single source of truth and the generator must not duplicate.
    *
    * Kept narrow on purpose: only types whose hand-written shape carries semantics the generator can't yet derive from
    * IDL alone (e.g. var-vs-def writability, the `Globals` `@JSGlobalScope` re-export). Everything else —
    * `EventTarget`, `Element`, `Node`, `Document`, etc. — gets generated, which keeps the spec as the source of truth.
    */
  private val engineOwnedInterfaces: Set[String] = Set.empty

  /** Collapse duplicate event entries to one canonical interface per event type.
    *
    * For each event type, the chosen interface is the one that lies furthest down the inheritance chain among the
    * candidates — the most specific. If two candidates are unrelated (rare, but happens in webref), the first one in
    * the input order wins (deterministic). An interface not in the IDL has no chain; it ranks as least specific so a
    * known interface always wins over an unknown one in the same type.
    */
  private def pickMostSpecificPerType(
      events: List[Webref.Event],
      idl: Webref.Idl,
  ): List[Webref.Event] =
    /** Depth in the inheritance chain, with `Event` (the root) at depth 0; unknown = -1. */
    def depth(iface: String): Int =
      def walk(name: String, d: Int, visited: Set[String]): Int =
        if visited.contains(name) then d
        else
          idl.interfaces.get(name) match
            case None        => -1
            case Some(iface) =>
              iface.inheritance match
                case None    => d
                case Some(p) => walk(p, d + 1, visited + name)
      walk(iface, 0, Set.empty)
    end depth

    events
      .groupBy(_.`type`)
      .toList
      .sortBy(_._1) // deterministic order across runs
      .map { (_, candidates) =>
        candidates.maxBy(e => depth(e.interface))
      }
  end pickMostSpecificPerType

  /** Collect attributes from every strict element's IDL chain into a single de-duplicated set.
    *
    * The first-pass strategy is intentionally simple: emit one global `Attrs` listing that's the union across all
    * strict elements. Per-element attribute *grouping* (so `input` sees only its own attrs and `<a>` sees only `<a>`
    * attrs) is a follow-up — for now the renderer test exercises the inheritance walk and de-dup behavior, which is the
    * genuinely tricky part.
    */
  private def strictAttrDefs(
      elements: List[Webref.Element],
      strict: Set[String],
      idl: Webref.Idl,
  ): List[AttrDef] =
    val elsByName = elements.iterator.map(e => e.name -> e.interface).toMap
    val seen      = scala.collection.mutable.LinkedHashMap.empty[String, AttrDef]
    strict.toList.flatMap(elsByName.get).foreach { iface =>
      DefBuilder.attributesFor(iface, idl).foreach { a =>
        seen.getOrElseUpdate(a.domName, a)
      }
    }
    seen.values.toList
  end strictAttrDefs

end Generator
