package ascent.domgen

/** Turns parsed webref data ([[Webref.Element]] / [[Webref.Event]] / [[Webref.Idl]]) into the render-ready
  * [[ElementDef]] / [[AttrDef]] / [[EventDef]] / [[FacadeDef]] model.
  *
  * This is where the two non-trivial transforms live: walking the IDL inheritance chain to collect an element's full
  * attribute set, and mapping event interfaces to our own `ascent.dom` facade type strings (the Laminar cross-platform
  * trick).
  */
object DefBuilder:

  /** HTML void elements (no closing tag / no children). webref doesn't flag these, so we keep the canonical list from
    * the HTML spec.
    */
  private val voidElements: Set[String] =
    Set("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

  // --- elements ---

  def elementDefs(elements: List[Webref.Element]): List[ElementDef] =
    elements.map { e =>
      ElementDef(
        scalaName = scalaName(e.name),
        domName = e.name,
        isVoid = voidElements.contains(e.name),
        interface = e.interface,
      )
    }

  // --- per-element attributes via IDL inheritance walk ---

  /** All attributes for an interface: its own plus every ancestor's plus every mixin folded in via
    * `Target includes Mixin;` statements at any level of the chain.
    *
    * De-duplicated by dom name (a subclass override wins; mixin attributes are added AFTER the host interface's own so
    * the host wins on collision). The walk terminates on a missing parent or a cycle (visited-set guard), so malformed
    * / partial IDL can't loop forever.
    */
  def attributesFor(interface: String, idl: Webref.Idl): List[AttrDef] =
    // Pre-index includes by target name for fast lookup during the walk.
    val mixinsByTarget: Map[String, List[String]] =
      idl.includes.groupBy(_.target).view.mapValues(_.map(_.mixin)).toMap

    /** Collect attributes from one interface PLUS any mixins it includes. Visited tracks mixin names too (a malformed
      * IDL with a mixin including itself shouldn't loop).
      */
    def attrsAt(name: String, visited: Set[String]): List[Webref.IdlAttribute] =
      if visited.contains(name) then Nil
      else
        idl.interfaces.get(name) match
          case None        => Nil
          case Some(iface) =>
            val ownAttrs   = iface.attributes
            val mixinAttrs = mixinsByTarget
              .getOrElse(name, Nil)
              .flatMap(m => attrsAt(m, visited + name))
            ownAttrs ++ mixinAttrs

    def walk(name: String, visited: Set[String]): List[Webref.IdlAttribute] =
      if visited.contains(name) then Nil
      else
        idl.interfaces.get(name) match
          case None        => Nil
          case Some(iface) =>
            // own + mixin attrs first so they win the later de-dup over inherited ones
            attrsAt(name, visited) ++ iface.inheritance.toList.flatMap(p => walk(p, visited + name))

    val seen = scala.collection.mutable.LinkedHashMap.empty[String, AttrDef]
    walk(interface, Set.empty).foreach { a =>
      // The IDL name is the JS PROPERTY name (e.g. `className`); convert to the HTML
      // ATTRIBUTE name (e.g. `class`) for `domName`, since that's what setAttribute uses.
      val htmlAttr = htmlAttributeName(a.name)
      seen.getOrElseUpdate(htmlAttr, AttrDef(scalaName(a.name), htmlAttr, codecFor(htmlAttr, a.idlType)))
    }
    seen.values.toList
  end attributesFor

  /** Map a JS property name from the IDL to its HTML attribute name. The asymmetry exists for historical reasons; only
    * a small set need explicit renames (`class`, `for`). Everything else is lowercased — HTML attribute names are
    * case-insensitive on the wire and all-lowercase by convention, while IDL property names are camelCased.
    */
  private val structuralRenames: Map[String, String] = Map(
    "className" -> "class",
    "htmlFor"   -> "for",
  )

  private[domgen] def htmlAttributeName(idlName: String): String =
    structuralRenames.getOrElse(idlName, idlName.toLowerCase)

  /** HTML attributes that the IDL types `boolean` but which are ENUMERATED `"true"`/`"false"` attributes on the wire —
    * they must serialize the explicit literal, not use presence coding. `draggable=""` / `spellcheck=""` resolve to the
    * "auto"/default state, not `true`. Keyed by HTML attribute (dom) name. (`contenteditable` is NOT here: webref types
    * it DOMString, so it already lands on StringAsIs.)
    */
  private val enumeratedTrueFalseAttrs: Set[String] = Set("draggable", "spellcheck")

  private def codecFor(htmlAttr: String, idlType: String): CodecRef =
    if enumeratedTrueFalseAttrs.contains(htmlAttr) then CodecRef.BooleanAsTrueFalse
    else
      idlType match
        case "boolean"                                             => CodecRef.BooleanAsAttrPresence
        case "long" | "unsigned long" | "short" | "unsigned short" => CodecRef.IntAsString
        case "double" | "float" | "unrestricted double"            => CodecRef.DoubleAsString
        case _                                                     => CodecRef.StringAsIs

  // --- platform-neutral structural DOM catalog (dom-core) ---

  /** Core interfaces the structural DOM trait catalog always includes, even though no `<element>` root's OWN ancestor
    * chain reaches them (they're siblings/consumers of the element tree, not ancestors of `HTMLElement`). Every
    * generated structural trait ultimately references at least one of these.
    */
  private val forcedCoreInterfaces: Set[String] =
    Set(
      "Node",
      "Element",
      "Document",
      "CharacterData",
      "Text",
      "Comment",
      "Attr",
      "Event",
      "EventTarget",
      "NodeList",
      "DOMTokenList",
      "HTMLCollection",
      "NamedNodeMap",
    )

  /** The interface closure the platform-neutral structural DOM catalog covers: every HTML + SVG element's interface
    * (from the webref element catalogs), plus their full ancestor chains, plus [[forcedCoreInterfaces]].
    *
    * Computed from the element catalogs rather than hardcoded, so re-vendoring webref naturally grows/shrinks the set —
    * this is the boundary between "genuinely part of the portable Node/Element/Document tree" (in) and "every other
    * webref interface, including hardware/media APIs with no relationship to the element tree" (out), per the decided
    * scope of the cross-platform DOM work.
    *
    * An element root whose interface is missing from `idl` is still recorded (its ancestor walk simply stops there) —
    * silently dropping it would hide a webref/generator mismatch rather than surface it.
    */
  def elementInterfaceClosure(
      htmlElements: List[Webref.Element],
      svgElements: List[Webref.Element],
      idl: Webref.Idl,
  ): Set[String] =
    val roots                                          = (htmlElements ++ svgElements).map(_.interface).toSet
    val closure                                        = scala.collection.mutable.LinkedHashSet.empty[String]
    def walk(name: String, visited: Set[String]): Unit =
      if !visited.contains(name) then
        closure += name
        idl.interfaces.get(name).flatMap(_.inheritance).foreach(p => walk(p, visited + name))
    roots.foreach(r => walk(r, Set.empty))
    closure ++= forcedCoreInterfaces
    closure.toSet
  end elementInterfaceClosure

  /** Platform-neutral type mapper for the structural DOM catalog — the sibling of [[scalaFacadeType]] used by
    * [[Renderer.structuralTraits]] / [[Renderer.memoryImpls]]. Never emits `js.*`; anything that isn't a primitive, an
    * in-scope structural interface, a known IDL enum, or a known IDL callback maps to the escape-hatch marker
    * `ascent.domcore.PlatformOpaque`, so a generated trait's signature always compiles even for members whose real type
    * is a rendering-only API (Canvas 2D, WebGL), a dictionary, or a union/sequence — none of which are part of the
    * portable structural surface.
    *
    * A known enum resolves to its REAL Scala 3 enum type (`ascent.domtypes.<Name>`, generated by
    * [[Renderer.enumTypes]]) rather than `String` — sound here (unlike [[scalaFacadeType]]'s enum handling) because
    * both dom-core backends (the JS adapter, the in-memory impl) do genuine string<->enum conversion at the boundary;
    * there's no `@js.native` erasure to route around.
    *
    * A known callback (e.g. `EventListener`, `FrameRequestCallback`) resolves to a plain Scala `FunctionN[...]` shape —
    * never `js.FunctionN` — recursing through THIS mapper for its own param/return types, mirroring
    * [[scalaFacadeType]]'s callback branch but staying scalajs-free. This is what lets `EventTarget.addEventListener`
    * declare its real WebIDL shape (`listener: Event => Unit`, not a `PlatformOpaque` stand-in) — implementing the spec
    * faithfully rather than working around it; any ergonomic wrapping (e.g. binding to ascent's own `AscentEvent`
    * instead of the raw `Event` trait) is a concern for the layer ABOVE dom-core, not dom-core itself.
    *
    * Resolution order is in-scope-membership FIRST, then the primitive table, then enums, then callbacks — an interface
    * name that happens to collide with a primitive keyword (doesn't happen in practice, but the mapper shouldn't rely
    * on that) must still resolve to itself when it's a real in-scope structural type.
    */
  def structuralType(idlType: String, inScope: Set[String], idl: Webref.Idl): String =
    if inScope.contains(idlType) then idlType
    else if idlType.contains(" | ") then
      // A union, surfaced by Webref.simpleIdlType as its member names joined by " | "
      // (e.g. "TrustedType | DOMString" for Element.setAttribute's real WebIDL param type).
      // Each member resolves through this SAME mapper independently — implementing the spec
      // faithfully as a genuine Scala 3 union rather than collapsing straight to PlatformOpaque.
      // Members that resolve to the same Scala type dedupe (two distinct unmodelled IDL names
      // both landing on PlatformOpaque must not render as "PlatformOpaque | PlatformOpaque").
      idlType.split(" \\| ").toList.map(m => structuralType(m, inScope, idl)).distinct.mkString(" | ")
    else if idlType.startsWith("sequence<") && idlType.endsWith(">") then
      // A single-argument sequence<T>, surfaced by Webref.simpleIdlType with that literal shape
      // (e.g. "sequence<DOMString>" for Element.getAttributeNames' real WebIDL return type).
      // Resolves to a real List[T] — List is available on jvm/js/native with no scalajs
      // dependency — rather than collapsing to PlatformOpaque; the element type recurses
      // through this SAME mapper.
      val elem = idlType.stripPrefix("sequence<").stripSuffix(">")
      s"List[${structuralType(elem, inScope, idl)}]"
    else
      idlType match
        case "DOMString" | "USVString" | "ByteString" | "CSSOMString"                                   => "String"
        case "boolean"                                                                                  => "Boolean"
        case "long" | "unsigned long" | "short" | "unsigned short" | "long long" | "unsigned long long" => "Int"
        case "double" | "float" | "unrestricted double" | "unrestricted float"                          => "Double"
        case "undefined" | "void"                                                                       => "Unit"
        case _                                                                                          =>
          idl.enums.find(_.name == idlType) match
            case Some(e) => s"ascent.domtypes.${e.name}"
            case None    =>
              idl.callbacks.find(_.name == idlType) match
                case Some(cb) =>
                  val args = cb.params.map(p => structuralType(p.idlType, inScope, idl))
                  val ret  = structuralType(cb.returnType, inScope, idl)
                  args.size match
                    case 0 => s"() => $ret"
                    // A single union-typed arg needs its own parens — `A | B => C` parses as
                    // `A | (B => C)` in Scala, not the intended `(A | B) => C`.
                    case 1 => s"${if args.head.contains(" | ") then s"(${args.head})" else args.head} => $ret"
                    case _ => s"(${args.mkString(", ")}) => $ret"
                case None => "ascent.domcore.PlatformOpaque"
  end structuralType

  // --- generated stand-alone interfaces (Interfaces.scala) ---

  /** Build one [[InterfaceDef]] per non-mixin interface in the IDL, skipping any name in `skipNames` (engine-owned
    * types like `Element` / `Document` and event facades that already live in `Facades.scala`).
    *
    * Each def carries:
    *   - the inheritance parent (if it points at another emitted interface — otherwise `None` so the generated class
    *     falls back to `extends js.Object`)
    *   - own + inherited + mixin attributes (via [[attributesFor]])
    *   - own + inherited + mixin operations (via [[methodsFor]])
    *
    * `typeOf` resolves an IDL type string to its Scala rendering — defaults to [[scalaFacadeType]] (the existing
    * `@js.native` facade path, what [[Renderer.interfaces]] consumes). The platform-neutral structural-trait path
    * ([[Renderer.structuralTraits]] / [[Renderer.memoryImpls]]) passes [[structuralType]] instead, so the SAME
    * inheritance walk / dedup logic produces either output shape — the walk itself has no JS-specific knowledge.
    *
    * The resulting list is what [[Renderer.interfaces]] (or the structural renderers) turns into Scala source.
    */
  def interfaceDefs(
      idl: Webref.Idl,
      skipNames: Set[String],
      typeOf: (String, Webref.Idl) => String = scalaFacadeType,
  ): List[InterfaceDef] =
    val emittedNames: Set[String] =
      idl.interfaces.collect {
        case (name, iface) if !iface.isMixin && !skipNames.contains(name) => name
      }.toSet
    idl.interfaces.toList.flatMap { case (name, iface) =>
      if iface.isMixin || skipNames.contains(name) then Nil
      else
        val parent =
          // Walk up the parent chain skipping any links that point at unknown / skipped
          // interfaces, so a child of `Element` (engine-owned, skipped) ends up with
          // `parent = None` rather than dangling at `Element` and failing to compile.
          iface.inheritance.flatMap { p =>
            if emittedNames.contains(p) then Some(p) else None
          }
        // OWN-level members only — own attributes + attributes from any included mixin —
        // since the generated Scala class inherits its parent's members via `extends`.
        // Re-emitting inherited members would force `override` on every collision and
        // explode the file size.
        //
        // We drop any own ATTRIBUTE whose name appears anywhere up the parent chain —
        // WebIDL allows a child to redeclare with a different writability or type, but
        // Scala 3 requires `override` for that. Following scalajs-dom's precedent, drop
        // the child redeclaration and let the inherited member supply the API.
        //
        // For OPERATIONS we dedup by (name, paramTypes) since overloads with different
        // signatures ARE legal in Scala 3 — a parent's `stroke(Path2D)` plus a child's
        // `stroke()` is fine (they're different methods). We only drop a child operation
        // if a parent already declares the exact same signature.
        val inheritedAttrNames  = collectInheritedAttrNames(name, idl)
        val inheritedMethodSigs = collectInheritedMethodSigs(name, idl, typeOf)
        val ownAttrs            = ownAttributesOf(name, idl)
          .filterNot((scalaAttrName, _, _, _, _) => inheritedAttrNames.contains(scalaAttrName))
          .map { (scalaAttrName, idlType, ro, reflected, htmlAttrName) =>
            FacadeMember(
              scalaAttrName,
              typeOf(idlType, idl),
              ro,
              reflected,
              Some(htmlAttrName),
              enumNameFor(idlType, idl),
            )
          }
        val ownAttrsDedup = scala.collection.mutable.LinkedHashMap.empty[String, FacadeMember]
        ownAttrs.foreach(m => ownAttrsDedup(m.name) = m)

        val ownMethods = ownMethodsOf(name, idl, typeOf)
          .filterNot(m => inheritedMethodSigs.contains((m.scalaName, m.params.map(_.scalaType))))
        // Dedup by (name, paramTypes) so overloads survive (e.g. `stroke()` vs
        // `stroke(Path2D)`).
        val ownMethodsDedup = scala.collection.mutable.LinkedHashMap.empty[(String, List[String]), MethodDef]
        ownMethods.foreach(m => ownMethodsDedup((m.scalaName, m.params.map(_.scalaType))) = m)

        Some(
          InterfaceDef(
            name = name,
            parent = parent,
            attributes = ownAttrsDedup.values.toList,
            methods = ownMethodsDedup.values.toList,
            inheritedMethodNames = inheritedMethodSigs.map(_._1),
          )
        )
    }
  end interfaceDefs

  /** Attribute names reachable through the inheritance chain (excluding `interface` itself), plus any mixin attributes
    * of those ancestors. [[interfaceDefs]] uses this to drop child redeclarations that would force an `override`
    * keyword on a `var`/`def`.
    */
  private def collectInheritedAttrNames(interface: String, idl: Webref.Idl): Set[String] =
    val acc                                            = scala.collection.mutable.Set.empty[String]
    def walk(name: String, visited: Set[String]): Unit =
      if visited.contains(name) then ()
      else
        idl.interfaces.get(name).foreach { iface =>
          ownAttributesOf(name, idl).foreach((scalaAttrName, _, _, _, _) => acc += scalaAttrName)
          iface.inheritance.foreach(p => walk(p, visited + name))
        }
    idl.interfaces.get(interface).flatMap(_.inheritance).foreach(p => walk(p, Set.empty))
    acc.toSet
  end collectInheritedAttrNames

  /** Method (name, paramTypes) signatures reachable through the inheritance chain. Used to drop child operations that
    * exactly shadow a parent's — those WOULD need `override`, but child overloads with DIFFERENT signatures than the
    * parent's are fine and survive.
    */
  private def collectInheritedMethodSigs(
      interface: String,
      idl: Webref.Idl,
      typeOf: (String, Webref.Idl) => String,
  ): Set[(String, List[String])] =
    val acc                                            = scala.collection.mutable.Set.empty[(String, List[String])]
    def walk(name: String, visited: Set[String]): Unit =
      if visited.contains(name) then ()
      else
        idl.interfaces.get(name).foreach { iface =>
          ownMethodsOf(name, idl, typeOf).foreach(m => acc += ((m.scalaName, m.params.map(_.scalaType))))
          iface.inheritance.foreach(p => walk(p, visited + name))
        }
    idl.interfaces.get(interface).flatMap(_.inheritance).foreach(p => walk(p, Set.empty))
    acc.toSet
  end collectInheritedMethodSigs

  /** Own attributes for an interface (excluding inheritance) PLUS attributes from any mixin it includes. Mixin attrs
    * are folded in here because mixins aren't emitted as their own classes — their members must appear on every
    * including interface.
    *
    * Carries the webref `[Reflect]` signal (`reflected`) and the HTML content-attribute name it reflects
    * (`htmlAttributeName(a.name)`) alongside the existing scalaName/idlType/readonly — consumed by the structural-trait
    * path's in-memory-impl generator ([[Renderer.memoryImpls]]) to auto-implement simple reflected properties.
    */
  private def ownAttributesOf(interface: String, idl: Webref.Idl): List[(String, String, Boolean, Boolean, String)] =
    val mixinNames = idl.includes.filter(_.target == interface).map(_.mixin)
    val ownIface   = idl.interfaces.get(interface).toList.flatMap(_.attributes)
    val mixinIface = mixinNames.flatMap(m => idl.interfaces.get(m).toList.flatMap(_.attributes))
    (ownIface ++ mixinIface).map(a =>
      (scalaName(a.name), a.idlType, a.readonly, a.reflected, htmlAttributeName(a.name))
    )

  /** Same shape for methods — own ops plus mixin ops.
    *
    * `typeOf` resolves an IDL type string to its Scala rendering — defaults to the existing JS-facade mapper
    * ([[scalaFacadeType]]) so every existing call site is behavior-unchanged. The platform-neutral structural-trait
    * path passes [[structuralType]] instead, so the SAME inheritance/mixin walk produces either output shape.
    */
  private def ownMethodsOf(
      interface: String,
      idl: Webref.Idl,
      typeOf: (String, Webref.Idl) => String,
  ): List[MethodDef] =
    val mixinNames = idl.includes.filter(_.target == interface).map(_.mixin)
    val ownIface   = idl.interfaces.get(interface).toList.flatMap(_.operations)
    val mixinIface = mixinNames.flatMap(m => idl.interfaces.get(m).toList.flatMap(_.operations))
    (ownIface ++ mixinIface).map(o =>
      MethodDef(
        scalaName = scalaName(o.name),
        domName = o.name,
        returnType = typeOf(o.returnType, idl),
        params = o.params.map(p => ParamDef(scalaName(p.name), typeOf(p.idlType, idl), p.optional)),
      )
    )
  end ownMethodsOf

  // --- dictionaries + enums ---

  /** Build a [[DictionaryDef]] per WebIDL dictionary, with field types resolved through the same [[scalaFacadeType]]
    * mapper used for interfaces (so `AbortSignal` ends up referencing the generated `AbortSignal` interface, etc.).
    */
  def dictionaryDefs(idl: Webref.Idl): List[DictionaryDef] =
    idl.dictionaries.map { d =>
      DictionaryDef(
        name = d.name,
        parent = d.inheritance,
        fields = d.fields.map(f =>
          DictionaryFieldDef(
            name = scalaName(f.name),
            scalaType = scalaFacadeType(f.idlType, idl),
            required = f.required,
          )
        ),
      )
    }

  /** Build an [[EnumDef]] per WebIDL enum. `values` are pairs of `(scalaName, domLiteral)` — hyphenated and
    * space-separated dom values like `"very-bad"` or `"two words"` get a camel-cased Scala name (`veryBad` /
    * `twoWords`) but the dom literal stays unchanged.
    */
  def enumDefs(idl: Webref.Idl): List[EnumDef] =
    idl.enums.map { e =>
      EnumDef(
        name = e.name,
        values = e.values.map(v => enumValueScalaName(v) -> v),
      )
    }

  /** Camel-case a WebIDL enum value into a Scala identifier. Splits on every char that isn't a Scala identifier-safe
    * character (letter / digit / underscore). Real spec values have spaces (`"two words"`), hyphens (`"very-bad"`),
    * slashes, dots, `@` symbols (`"invalid @version value"`) and more.
    *
    * Empty string becomes empty (the Renderer then maps to `empty` and dedups).
    */
  private def enumValueScalaName(s: String): String =
    val parts = s.split("[^A-Za-z0-9_]+").toList.filter(_.nonEmpty)
    parts match
      case Nil          => s // empty string — caller mangles
      case head :: tail => head + tail.map(capitalize).mkString

  // --- per-interface methods via IDL inheritance + mixin walk ---

  /** All operations for an interface: its own plus every ancestor's plus every mixin folded in via
    * `Target includes Mixin;` statements. Same walk shape as [[attributesFor]] — operations dedupe by name (override
    * shadows base).
    */
  def methodsFor(interface: String, idl: Webref.Idl): List[MethodDef] =
    val mixinsByTarget: Map[String, List[String]] =
      idl.includes.groupBy(_.target).view.mapValues(_.map(_.mixin)).toMap

    def opsAt(name: String, visited: Set[String]): List[Webref.IdlOperation] =
      if visited.contains(name) then Nil
      else
        idl.interfaces.get(name) match
          case None        => Nil
          case Some(iface) =>
            val mixinOps = mixinsByTarget
              .getOrElse(name, Nil)
              .flatMap(m => opsAt(m, visited + name))
            iface.operations ++ mixinOps

    def walk(name: String, visited: Set[String]): List[Webref.IdlOperation] =
      if visited.contains(name) then Nil
      else
        idl.interfaces.get(name) match
          case None        => Nil
          case Some(iface) =>
            opsAt(name, visited) ++ iface.inheritance.toList.flatMap(p => walk(p, visited + name))

    // Dedup by (name, paramTypes) so overloads survive — WebIDL declares overloads as
    // separate `operation` members with the same name and different signatures (e.g.
    // `stroke()` and `stroke(Path2D path)` on CanvasDrawPath). Scala 3 supports
    // overloads natively; emitting both gives users access to either form.
    val seen = scala.collection.mutable.LinkedHashMap.empty[(String, List[String]), MethodDef]
    walk(interface, Set.empty).foreach { o =>
      val md = MethodDef(
        scalaName = scalaName(o.name),
        domName = o.name,
        returnType = scalaFacadeType(o.returnType, idl),
        params = o.params.map(p => ParamDef(scalaName(p.name), scalaFacadeType(p.idlType, idl), p.optional)),
      )
      val key = (md.scalaName, md.params.map(_.scalaType))
      seen.getOrElseUpdate(key, md)
    }
    seen.values.toList
  end methodsFor

  // --- events ---

  def eventDefs(events: List[Webref.Event]): List[EventDef] =
    events.map { e =>
      EventDef(
        scalaName = handlerName(e.`type`),
        domName = e.`type`,
        eventTypeString = facadeType(e.interface),
        bubbles = e.targets.exists(_.bubbles.contains(true)),
      )
    }

  /** Our own facade type string for an event interface, e.g. `PointerEvent` → `ascent.dom.PointerEvent`. */
  private def facadeType(interface: String): String = s"ascent.dom.$interface"

  // --- event facade hierarchy ---

  /** One [[FacadeDef]] per event interface referenced by any event, plus all transitively-named ancestors (so
    * `MouseEvent`'s `UIEvent`/`Event` parents are emitted even if no event names them directly). Members and parent
    * come from the merged IDL.
    */
  def facadeDefs(events: List[Webref.Event], idl: Webref.Idl): List[FacadeDef] =
    val roots                       = events.map(_.interface).toSet
    val closure                     = scala.collection.mutable.LinkedHashSet.empty[String]
    def collect(name: String): Unit =
      if !closure.contains(name) then
        idl.interfaces.get(name).foreach { iface =>
          closure += name
          iface.inheritance.foreach(collect)
        }
    roots.foreach(collect)
    closure.toList.map { name =>
      val iface = idl.interfaces(name)
      // Operations are own-only — same logic as InterfaceDef. Drop any that would
      // override an inherited (name, sig) exactly. Different overloads of an inherited
      // name are fine and survive.
      val parentMethodSigs =
        iface.inheritance.toList
          .flatMap(p => methodsFor(p, idl).map(m => (m.scalaName, m.params.map(_.scalaType))))
          .toSet
      val ownOps = iface.operations
        .map(o =>
          MethodDef(
            scalaName = scalaName(o.name),
            domName = o.name,
            returnType = scalaFacadeType(o.returnType, idl),
            params = o.params.map(p => ParamDef(scalaName(p.name), scalaFacadeType(p.idlType, idl), p.optional)),
          )
        )
        .filterNot(m => parentMethodSigs.contains((m.scalaName, m.params.map(_.scalaType))))
      FacadeDef(
        interface = name,
        parent = iface.inheritance.filter(idl.interfaces.contains),
        members = facadeMembers(iface, idl),
        methods = ownOps,
      )
    }
  end facadeDefs

  /** Members for a facade, dropping any that would clash with an inherited member of a different scala type. WebIDL
    * allows a subinterface to redeclare an inherited attribute with a different type (`BeforeUnloadEvent.returnValue:
    * DOMString` vs `Event.returnValue: boolean`). Scala's `@js.native` rules require the child to override with a
    * *matching* type, so the only safe thing for a faithful generator is to drop the child entry — users wanting the
    * spec-correct value can drop to dynamic. Same-typed redeclarations are kept (and emit a Scala override).
    */
  private def facadeMembers(iface: Webref.IdlInterface, idl: Webref.Idl): List[FacadeMember] =
    // Idl-AWARE resolution (same as the operation path in facadeDefs): a member typed as a known
    // interface (e.g. `DragEvent.dataTransfer: DataTransfer`, `Event.currentTarget: EventTarget`)
    // resolves to that interface's typed name rather than falling back to `js.Any`. The referenced
    // types are co-emitted in Interfaces.scala/Facades.scala, so the symbols resolve. Unknown /
    // union / sequence types still fall back to `js.Any`.
    val inheritedTypes: Map[String, String] =
      val acc                                            = scala.collection.mutable.LinkedHashMap.empty[String, String]
      def walk(name: String, visited: Set[String]): Unit =
        if !visited.contains(name) then
          idl.interfaces.get(name).foreach { i =>
            i.attributes.foreach(a => acc.getOrElseUpdate(a.name, scalaFacadeType(a.idlType, idl)))
            i.inheritance.foreach(p => walk(p, visited + name))
          }
      iface.inheritance.foreach(p => walk(p, Set(iface.name)))
      acc.toMap
    end inheritedTypes
    iface.attributes.flatMap { a =>
      val childType = scalaFacadeType(a.idlType, idl)
      inheritedTypes.get(a.name) match
        case Some(parentType) if parentType != childType => None // type clash: drop
        case _ => Some(FacadeMember(a.name, childType, enumType = enumNameFor(a.idlType, idl)))
    }
  end facadeMembers

  /** `Some(idlType)` when `idlType` names a known WebIDL `enum` block, else `None`. Used to populate
    * [[FacadeMember.enumType]] so [[Renderer.enumAccessors]] can emit an additive typed-enum accessor alongside the
    * native `String`-typed member.
    */
  private def enumNameFor(idlType: String, idl: Webref.Idl): Option[String] =
    idl.enums.find(_.name == idlType).map(_.name)

  /** Map a WebIDL type to its Scala facade type.
    *
    * Coverage:
    *   - primitives map to their canonical Scala equivalents
    *   - `undefined` → `Unit` (return type for void operations)
    *   - `DOMString` / `USVString` / `ByteString` / `CSSOMString` → `String`
    *   - callback typedefs (e.g. `FrameRequestCallback`, `EventListener`) → typed `js.Function1[..., R]` reflecting the
    *     callback's actual shape — looked up via the `Idl.callbacks` registry
    *   - any other named type that we know is an interface → self-reference (`EventTarget`, `Blob`, etc.) — emitted in
    *     the same `Interfaces.scala` so the symbol resolves
    *   - everything else (unions, sequences, generics, unknown names) → `js.Any`
    *
    * The legacy no-arg overload exists for callers that don't yet thread an [[Idl]] through (event facades, attribute
    * tests). It treats every name as opaque.
    */
  private[domgen] def scalaFacadeType(idlType: String): String = baseScalaType(idlType)

  /** Idl-aware version: looks up callbacks and known interfaces to produce richer types.
    *
    * Resolution order:
    *   1. callback typedef (function shape) — emits `js.FunctionN[..., R]`
    *   2. known interface in `idl.interfaces` — emits the bare interface name (the generator co-emits these in
    *      `Interfaces.scala`, so the symbol resolves)
    *   3. primitive / DOMString / fall-through to `js.Any`
    */
  private[domgen] def scalaFacadeType(idlType: String, idl: Webref.Idl): String =
    idl.callbacks.find(_.name == idlType) match
      case Some(cb) =>
        // Recursively resolve param/return types — a callback that takes an Event should
        // get `Function1[Event, Unit]`, not `Function1[js.Any, Unit]`.
        val args = cb.params.map(p => scalaFacadeType(p.idlType, idl))
        val ret  = scalaFacadeType(cb.returnType, idl)
        args.size match
          case 0 => s"scala.scalajs.js.Function0[$ret]"
          case 1 => s"scala.scalajs.js.Function1[${args(0)}, $ret]"
          case 2 => s"scala.scalajs.js.Function2[${args(0)}, ${args(1)}, $ret]"
          case 3 => s"scala.scalajs.js.Function3[${args(0)}, ${args(1)}, ${args(2)}, $ret]"
          case _ => "scala.scalajs.js.Function" // bigger arities are rare in callback shapes
      case scala.None =>
        // Resolution order beyond callbacks:
        //   1. interface in idl.interfaces (Interfaces.scala / Facades.scala)
        //   2. dictionary in idl.dictionaries (Dictionaries.scala)
        //   3. enum in idl.enums — but enums are stringly-typed, so the Scala type is
        //      `String`; the call site references the literal via `MyEnum.foo`.
        //   4. otherwise primitives / fall-through to `js.Any`.
        if idl.interfaces.contains(idlType) then idlType
        else if idl.dictionaries.exists(_.name == idlType) then idlType
        else if idl.enums.exists(_.name == idlType) then "String"
        else baseScalaType(idlType)

  private def baseScalaType(idlType: String): String = idlType match
    case "boolean"                                                                                  => "Boolean"
    case "long" | "unsigned long" | "short" | "unsigned short" | "long long" | "unsigned long long" => "Int"
    case "double" | "float" | "unrestricted double" | "unrestricted float"                          => "Double"
    case "undefined" | "void"                                                                       => "Unit"
    case "DOMString" | "USVString" | "ByteString" | "CSSOMString"                                   => "String"
    // Common typedefs the spec uses pervasively. WebIDL `typedef double DOMHighResTimeStamp`
    // resolves to `double`, but we don't yet decode `typedef` blocks; aliasing the most
    // common ones here keeps callbacks like `FrameRequestCallback(DOMHighResTimeStamp)`
    // typed as `Double` rather than `js.Any`.
    case "DOMHighResTimeStamp" | "EpochTimeStamp" => "Double"
    case "DOMTimeStamp"                           => "Double"
    case "any" | "object"                         => "scala.scalajs.js.Any"
    case _                                        => "scala.scalajs.js.Any"

  // --- name mangling ---

  /** A Scala-safe identifier for a dom name. Hyphens become camelCase; reserved/awkward names get a known-good alias
    * (e.g. `class` → `className`); otherwise the name passes through.
    */
  private[domgen] def scalaName(domName: String): String =
    val camel = hyphenToCamel(domName)
    camel match
      case "class" => "className"
      case other   => other

  /** Known multi-word event names that have no separator in the spec but are conventionally camel-cased in handler
    * names: `keydown` → `onKeyDown`, `mousemove` → `onMouseMove`. The generator splits these so the emitted handler
    * matches what JS developers expect to write.
    */
  private val eventWordBoundaries: Map[String, String] = Map(
    "keydown"            -> "keyDown",
    "keyup"              -> "keyUp",
    "keypress"           -> "keyPress",
    "mousedown"          -> "mouseDown",
    "mouseup"            -> "mouseUp",
    "mousemove"          -> "mouseMove",
    "mouseover"          -> "mouseOver",
    "mouseout"           -> "mouseOut",
    "mouseenter"         -> "mouseEnter",
    "mouseleave"         -> "mouseLeave",
    "pointerdown"        -> "pointerDown",
    "pointerup"          -> "pointerUp",
    "pointermove"        -> "pointerMove",
    "pointerover"        -> "pointerOver",
    "pointerout"         -> "pointerOut",
    "pointerenter"       -> "pointerEnter",
    "pointerleave"       -> "pointerLeave",
    "pointercancel"      -> "pointerCancel",
    "touchstart"         -> "touchStart",
    "touchend"           -> "touchEnd",
    "touchmove"          -> "touchMove",
    "touchcancel"        -> "touchCancel",
    "dblclick"           -> "dblClick",
    "contextmenu"        -> "contextMenu",
    "dragstart"          -> "dragStart",
    "dragend"            -> "dragEnd",
    "dragenter"          -> "dragEnter",
    "dragleave"          -> "dragLeave",
    "dragover"           -> "dragOver",
    "loadstart"          -> "loadStart",
    "loadend"            -> "loadEnd",
    "loadeddata"         -> "loadedData",
    "loadedmetadata"     -> "loadedMetadata",
    "canplay"            -> "canPlay",
    "canplaythrough"     -> "canPlayThrough",
    "ratechange"         -> "rateChange",
    "timeupdate"         -> "timeUpdate",
    "volumechange"       -> "volumeChange",
    "durationchange"     -> "durationChange",
    "animationstart"     -> "animationStart",
    "animationend"       -> "animationEnd",
    "animationiteration" -> "animationIteration",
    "animationcancel"    -> "animationCancel",
    "transitionend"      -> "transitionEnd",
    "transitioncancel"   -> "transitionCancel",
    "transitionstart"    -> "transitionStart",
    "transitionrun"      -> "transitionRun",
    "focusin"            -> "focusIn",
    "focusout"           -> "focusOut",
    "cuechange"          -> "cueChange",
    "selectstart"        -> "selectStart",
    "selectionchange"    -> "selectionChange",
    "compositionstart"   -> "compositionStart",
    "compositionend"     -> "compositionEnd",
    "compositionupdate"  -> "compositionUpdate",
    "beforeinput"        -> "beforeInput",
    "beforeunload"       -> "beforeUnload",
  )

  /** `keydown` → `onKeyDown`, `click` → `onClick`, `pointer-leave` → `onPointerLeave`. */
  private[domgen] def handlerName(eventType: String): String =
    val normalized = eventWordBoundaries.getOrElse(eventType, eventType)
    "on" + capitalize(hyphenToCamel(normalized))

  private def hyphenToCamel(s: String): String =
    val parts = s.split("-").toList
    parts match
      case Nil          => s
      case head :: tail => head + tail.map(capitalize).mkString

  private def capitalize(s: String): String =
    if s.isEmpty then s else s.head.toUpper.toString + s.tail

end DefBuilder
