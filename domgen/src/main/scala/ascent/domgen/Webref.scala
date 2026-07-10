package ascent.domgen

import zio.*
import zio.json.*
import zio.json.ast.Json

/** Typed views over the vendored W3C `webref` JSON, decoded with zio-json.
  *
  * We model only the fields the generator consumes and let unknown fields fall away — webref carries far more than we
  * need. Parsing returns a ZIO effect with a typed [[WebrefParseError]] channel — `domgen` is tooling that already
  * depends on ZIO (via zio-json), so we use idiomatic effects rather than throwing.
  */
object Webref:

  /** Lift a zio-json decode `Either` into a ZIO effect with a typed [[WebrefParseError]] channel. */
  private def decode[A](source: String, result: Either[String, A]): IO[WebrefParseError, A] =
    ZIO.fromEither(result).mapError(WebrefParseError(source, _))

  // --- elements/html.json : { spec, elements: [ {name, interface, href} ] } ---

  final case class Element(name: String, interface: String, href: Option[String] = None)
  object Element:
    given JsonDecoder[Element] = DeriveJsonDecoder.gen[Element]

  private final case class ElementsFile(elements: List[Element])
  private object ElementsFile:
    given JsonDecoder[ElementsFile] = DeriveJsonDecoder.gen[ElementsFile]

  def parseElements(json: String): IO[WebrefParseError, List[Element]] =
    decode("elements", json.fromJson[ElementsFile]).map(_.elements)

  // --- events.json : [ {type, interface, targets: [{target, bubbles?}], href} ] ---

  final case class EventTarget(target: String, bubbles: Option[Boolean] = None)
  object EventTarget:
    given JsonDecoder[EventTarget] = DeriveJsonDecoder.gen[EventTarget]

  final case class Event(
      `type`: String,
      interface: String,
      targets: List[EventTarget] = Nil,
      href: Option[String] = None,
  )
  object Event:
    given JsonDecoder[Event] = DeriveJsonDecoder.gen[Event]

  def parseEvents(json: String): IO[WebrefParseError, List[Event]] =
    decode("events", json.fromJson[List[Event]])

  // --- idlparsed/*.json : { spec, idlparsed: { idlNames: { Name -> Interface } } } ---

  /** A WebIDL attribute member, reduced to the bits the generator needs.
    *
    * `readonly` distinguishes `readonly attribute T name;` (Scala `def`) from `attribute T name;` (Scala `var`).
    * Without this, every generated facade attribute would be read-only and the engine couldn't write
    * `text.data = "..."` etc.
    *
    * `reflected` mirrors the spec's `[Reflect]` extended attribute: the getter/setter is defined purely as "read/write
    * the same-named (or `[Reflect=x]`-mapped) content attribute," with no custom behavior.
    * `HTMLInputElement.placeholder` carries it; `HTMLInputElement.checked`/`.value` do not (they have genuine internal
    * state distinct from the DOM attribute). The in-memory DOM backend's generator uses this to auto-implement
    * reflected properties as reads/writes against the element's attribute map, with zero hand-written code.
    */
  final case class IdlAttribute(name: String, idlType: String, readonly: Boolean = false, reflected: Boolean = false)

  /** A WebIDL operation parameter (argument). */
  final case class IdlParam(name: String, idlType: String, optional: Boolean)

  /** A WebIDL operation (method) member. The return type is captured as a simple IDL type string (e.g. `"DOMString"`,
    * `"undefined"`, `"RenderingContext"`); union / generic return types reduce to their primary `idlType` field,
    * mirroring the [[simpleIdlType]] policy for attributes.
    */
  final case class IdlOperation(name: String, returnType: String, params: List[IdlParam])

  /** A parsed WebIDL interface OR `interface mixin`: its inheritance parent (if any), attribute members, operation
    * members, and a flag distinguishing the two kinds. Mixins don't appear by name in user code — they're folded into a
    * target interface via [[IdlIncludes]] statements (`Target includes Mixin;`). The walks in
    * [[DefBuilder.attributesFor]] / [[DefBuilder.methodsFor]] use both.
    */
  final case class IdlInterface(
      name: String,
      inheritance: Option[String],
      attributes: List[IdlAttribute],
      operations: List[IdlOperation] = Nil,
      isMixin: Boolean = false,
  )

  /** A WebIDL `Target includes Mixin;` statement. Surfaced separately because (a) the same target can include several
    * mixins and (b) `idlExtendedNames` in webref groups every extension-shaped definition (partials, includes, callback
    * constants) under one map.
    */
  final case class IdlIncludes(target: String, mixin: String)

  /** A WebIDL callback or callback interface, reduced to a plain function shape: `name (params) -> returnType`. Both
    * `callback Foo = R (P);` and `callback interface Foo { R handleEvent(P); }` decode to this — the latter has a fixed
    * implicit operation name (always `handleEvent`) but the call-site contract is the same: a function. The type mapper
    * renders these as `js.Function1[..., R]`.
    */
  final case class IdlCallback(name: String, returnType: String, params: List[IdlParam])

  /** A field of a [[IdlDictionary]]. `required` distinguishes `required boolean foo;` (Scala: no default) from
    * `boolean foo = false;` (Scala: default arg).
    */
  final case class IdlField(name: String, idlType: String, required: Boolean)

  /** A WebIDL `dictionary` block — JS option-object shape passed to APIs that take a named-arg config (e.g.
    * `AddEventListenerOptions`, `KeyboardEventInit`, `CanvasRenderingContext2DSettings`). Generator renders these as
    * `@js.native trait`s users construct via `js.Dynamic.literal(...)`-style factories.
    */
  final case class IdlDictionary(
      name: String,
      inheritance: Option[String],
      fields: List[IdlField],
  )

  /** A WebIDL `enum` block. The IDL declares a stringly-typed enum (e.g. `enum ShadowRootMode { "open", "closed" }`).
    * Generator renders these as a Scala `object` with `inline val open = "open"` etc. — a compile-time-checked façade
    * over the underlying string.
    */
  final case class IdlEnum(name: String, values: List[String])

  /** All interfaces, mixins, includes statements, callbacks, dictionaries, and enums from one or more idlparsed spec
    * files.
    */
  final case class Idl(
      interfaces: Map[String, IdlInterface],
      includes: List[IdlIncludes] = Nil,
      callbacks: List[IdlCallback] = Nil,
      dictionaries: List[IdlDictionary] = Nil,
      enums: List[IdlEnum] = Nil,
  )

  // The raw shapes we decode before reducing. `idlType` is polymorphic (string | object | union)
  // in webref, so we capture it as Json and extract the simple `idlType` string when present.
  private final case class RawArgument(
      `type`: String,
      name: Option[String],
      idlType: Option[Json],
      optional: Option[Boolean],
  )
  private object RawArgument:
    given JsonDecoder[RawArgument] = DeriveJsonDecoder.gen[RawArgument]

  /** One entry in a member's `extAttrs` array — webref's extended-attribute annotations (`[Reflect]`, `[CEReactions]`,
    * etc). We only consume the `name`; `rhs`/`arguments` carry extended-attribute parameters we don't yet need.
    */
  private final case class RawExtAttr(name: String)
  private object RawExtAttr:
    given JsonDecoder[RawExtAttr] = DeriveJsonDecoder.gen[RawExtAttr]

  private final case class RawMember(
      `type`: String,
      name: Option[String],
      idlType: Option[Json],
      arguments: Option[List[RawArgument]],
      readonly: Option[Boolean] = None,
      // Dictionary-field-only: webref marks `required boolean foo` with `required: true`,
      // and a default-valued field `boolean foo = false` keeps `required: false`.
      required: Option[Boolean] = None,
      // Polymorphic across member kinds: enum-value entries carry the literal here as a
      // string; const members carry the default as a number/bool. We capture the raw Json
      // and decode to a string at the use-site (an enum match needs Json.Str specifically).
      value: Option[Json] = None,
      // Attribute-only: carries `[Reflect]` when present — see IdlAttribute.reflected.
      extAttrs: List[RawExtAttr] = Nil,
  )
  private object RawMember:
    given JsonDecoder[RawMember] = DeriveJsonDecoder.gen[RawMember]

  private final case class RawInterface(
      `type`: Option[String],
      name: Option[String],
      inheritance: Option[String],
      members: List[RawMember] = Nil,
      // For `callback` blocks (top-level bare callbacks): arguments live at this level
      // rather than under a member. `idlType` is the return type.
      idlType: Option[Json] = None,
      arguments: Option[List[RawArgument]] = None,
      // For `enum` blocks: webref puts the enum literals in `values: [{value: "open"}]`
      // rather than under `members`. Each entry is a RawMember with type="enum-value".
      values: Option[List[RawMember]] = None,
  )
  private object RawInterface:
    given JsonDecoder[RawInterface] = DeriveJsonDecoder.gen[RawInterface]

  /** A single entry inside `idlExtendedNames` — webref groups partials, includes, and other extension-shaped
    * definitions under one map. We consume:
    *   - `type == "includes"` (a `Target includes Mixin;` statement)
    *   - `type == "interface"` and `type == "interface mixin"` with `partial: true` (additional members to fold into
    *     the named target)
    */
  private final case class RawExtended(
      `type`: String,
      target: Option[String],
      includes: Option[String],
      name: Option[String] = None,
      partial: Option[Boolean] = None,
      members: Option[List[RawMember]] = None,
  )
  private object RawExtended:
    given JsonDecoder[RawExtended] = DeriveJsonDecoder.gen[RawExtended]

  private final case class RawIdlParsed(
      idlNames: Map[String, RawInterface] = Map.empty,
      idlExtendedNames: Map[String, List[RawExtended]] = Map.empty,
  )
  private object RawIdlParsed:
    given JsonDecoder[RawIdlParsed] = DeriveJsonDecoder.gen[RawIdlParsed]

  private final case class RawIdlFile(idlparsed: RawIdlParsed)
  private object RawIdlFile:
    given JsonDecoder[RawIdlFile] = DeriveJsonDecoder.gen[RawIdlFile]

  /** One member of a union's `idlType` array, e.g. `{ "idlType": "TrustedType" }`. WebIDL forbids directly nesting
    * unions — union members are always flattened to plain (non-union) types by the spec itself — so this is never
    * itself a union and needs no recursive decoder.
    */
  private final case class UnionMemberShape(idlType: String)
  private object UnionMemberShape:
    given JsonDecoder[UnionMemberShape] = DeriveJsonDecoder.gen[UnionMemberShape]

  /** The shape of a member's `idlType` json object: a `union` flag plus a nested `idlType` payload that's a plain
    * type-name STRING for the simple case, or an ARRAY of [[UnionMemberShape]] (one per member) when `union` is true —
    * e.g. `(TrustedType or DOMString)` decodes to `IdlTypeShape(union = true, idlType = Right(List(...)))`. The
    * polymorphic payload is captured as `Either[String, List[UnionMemberShape]]`, decoded via `JsonDecoder`'s own
    * `.orElse`/`.map` combinators (try the `String` shape, fall back to the array shape on failure) — no manual
    * `Json.Str`/`Json.Obj`/`Json.Arr` matching. `zio.json.JsonDecoder.either` is deliberately NOT used here — it's
    * intended for encodings that distinguish `Left`/`Right` structurally (e.g. a wrapper object), not "decode raw value
    * as A, else as B", and mis-decodes this shape. Other fields webref includes on the same object (`type`, `nullable`,
    * ...) are simply ignored by the derived decoder.
    */
  private final case class IdlTypeShape(
      idlType: Either[String, List[UnionMemberShape]],
      union: Boolean = false,
      generic: String = "",
  )
  private object IdlTypeShape:
    given JsonDecoder[Either[String, List[UnionMemberShape]]] =
      JsonDecoder[String].map(Left(_)).orElse(JsonDecoder[List[UnionMemberShape]].map(Right(_)))
    given JsonDecoder[IdlTypeShape] = DeriveJsonDecoder.gen[IdlTypeShape]

  /** Pull the simple IDL type name(s) out of a member's `idlType` json, via [[IdlTypeShape]]'s codec.
    *
    * The ordinary (non-generic, non-union) case decodes to a bare type name (e.g. `"DOMString"`). A GENUINE union
    * (`(TrustedType or DOMString)`, `union: true`) decodes to its member names joined by `" | "` (`"TrustedType |
    * DOMString"`) rather than collapsing to an opaque placeholder — [[DefBuilder.structuralType]] splits this back
    * apart and resolves each member independently, so a union-typed member's generated Scala signature is a REAL union
    * type, not a single escape hatch. A single-argument `sequence<T>` generic (`generic: "sequence"`) decodes to
    * `"sequence<T>"` (e.g. `"sequence<DOMString>"`) — [[DefBuilder.structuralType]] recognizes that literal prefix and
    * resolves it to a real `List[T]`, mirroring the union treatment rather than collapsing to `PlatformOpaque`.
    * Multi-argument generics (`record<K, V>`) and other generic kinds (`Promise<T>`, `FrozenArray<T>`) aren't modeled
    * and yield `None`, same as before.
    *
    * BOTH the `union` and `generic` payload shapes are array-of-[[UnionMemberShape]] on the wire — a WebIDL
    * `sequence<DOMString>` decodes as `{ "generic": "sequence", "union": false, "idlType": [ { "idlType": "DOMString" }
    * ] }`, superficially identical to a union's array payload. Checking `union`/`generic` EXPLICITLY (rather than
    * inferring from payload shape alone) is what keeps these two cases from being confused with each other or with an
    * unmodeled generic.
    */
  private def simpleIdlType(idlType: Json): Option[String] =
    idlType.as[IdlTypeShape] match
      case Right(shape) if shape.union =>
        shape.idlType match
          case Right(members) => Some(members.map(_.idlType).mkString(" | "))
          case Left(name)     => Some(name) // pathological: union: true but a bare-string payload
      case Right(shape) if shape.generic == "sequence" =>
        shape.idlType match
          case Right(List(single)) => Some(s"sequence<${single.idlType}>")
          case _                   => None // record<K,V> or another shape sequence<T> can't take
      case Right(shape) =>
        shape.idlType.left.toOption // no union/sequence — only the plain-string payload is a simple type
      case Left(_) => idlType.as[String].toOption

  def parseIdl(json: String): IO[WebrefParseError, Idl] =
    decode("idlparsed", json.fromJson[RawIdlFile]).map { file =>
      // Both `interface` and `interface mixin` go into the interfaces map. Distinguishing
      // them at the type level keeps DefBuilder honest about what's user-visible vs. what
      // gets folded in via `includes`.
      // Pull params out of either member-level `arguments` (operations) or interface-level
      // `arguments` (top-level bare `callback` blocks). A union / generic / sequence type
      // we can't reduce to a simple name still produces a parameter — we just give it the
      // synthetic `any` type so the mapper renders `js.Any`. Dropping the param entirely
      // would change the operation's arity and break the call site.
      def paramsOf(args: Option[List[RawArgument]]): List[IdlParam] =
        args.getOrElse(Nil).collect { case RawArgument("argument", Some(an), Some(at), opt) =>
          val ty = simpleIdlType(at).getOrElse("any")
          IdlParam(an, ty, opt.getOrElse(false))
        }

      val interfaces = file.idlparsed.idlNames.collect {
        case (name, raw) if raw.`type`.contains("interface") || raw.`type`.contains("interface mixin") =>
          val attrs = raw.members.collect { case RawMember("attribute", Some(n), Some(t), _, ro, _, _, ext) =>
            // Same fall-back-to-`any` story as operations: a union-typed attribute
            // (`attribute (Foo or Bar) baz`) still surfaces, just with `js.Any` as its
            // declared type.
            IdlAttribute(n, simpleIdlType(t).getOrElse("any"), ro.getOrElse(false), ext.exists(_.name == "Reflect"))
          }
          val ops = raw.members.collect {
            case RawMember("operation", Some(n), Some(t), maybeArgs, _, _, _, _) if n.nonEmpty =>
              // Skip IDL "stringifiers", anonymous "getter Foo (...)", "setter ...", and
              // "deleter ..." operations — these have no member name and need special-case
              // handling in Scala (apply/update operators) which we don't yet emit.
              // Union / generic return types fall back to `any` so the operation still
              // surfaces with the right arity.
              IdlOperation(n, simpleIdlType(t).getOrElse("any"), paramsOf(maybeArgs))
          }
          val isMixin = raw.`type`.contains("interface mixin")
          name -> IdlInterface(name, raw.inheritance, attrs, ops, isMixin)
      }

      // Two callback shapes:
      //   - `callback Foo = R (P);` → top-level `idlType` + `arguments`
      //   - `callback interface Bar { R handleEvent(P); }` → one operation member
      val bareCallbacks = file.idlparsed.idlNames.collect {
        case (name, raw) if raw.`type`.contains("callback") && !raw.`type`.contains("interface") =>
          val ret = raw.idlType.flatMap(simpleIdlType).getOrElse("undefined")
          IdlCallback(name, ret, paramsOf(raw.arguments))
      }
      val interfaceCallbacks = file.idlparsed.idlNames.collect {
        case (name, raw) if raw.`type`.contains("callback interface") =>
          // Conventionally one op called handleEvent — pick the first operation member
          // and treat the interface as a function with that op's signature.
          val opOpt = raw.members.collectFirst {
            case RawMember("operation", _, Some(t), maybeArgs, _, _, _, _) if simpleIdlType(t).isDefined =>
              IdlCallback(name, simpleIdlType(t).get, paramsOf(maybeArgs))
          }
          opOpt
      }.flatten

      // Dictionaries: `dictionary Name : Parent? { field; field; ... }`. Decoded into
      // [[IdlDictionary]] with each field's IDL type + required flag; the renderer emits
      // a `@js.native trait` per dictionary.
      val dictionaries = file.idlparsed.idlNames.collect {
        case (name, raw) if raw.`type`.contains("dictionary") =>
          val fields = raw.members.collect { case RawMember("field", Some(n), Some(t), _, _, req, _, _) =>
            IdlField(n, simpleIdlType(t).getOrElse("any"), req.getOrElse(false))
          }
          IdlDictionary(name, raw.inheritance, fields)
      }.toList

      // Enums: `enum Name { "a", "b", ... }`. Decoded into [[IdlEnum]] with the string
      // literals; the renderer emits a Scala `object` with `inline val a = "a"` etc.
      val enums = file.idlparsed.idlNames.collect {
        case (name, raw) if raw.`type`.contains("enum") =>
          val values = raw.values.getOrElse(Nil).collect {
            case RawMember("enum-value", _, _, _, _, _, Some(Json.Str(v)), _) => v
          }
          IdlEnum(name, values)
      }.toList
      // `idlExtendedNames` contains a list per target name. We consume two flavours:
      //   - `type == "includes"`             → reconstruct (target, mixin) pairs
      //   - `type == "interface" / "interface mixin"` with `partial: true` → merge the
      //     extra members into the existing entry in `interfaces`.
      val extended = file.idlparsed.idlExtendedNames.toList.flatMap { case (target, entries) =>
        entries.map(target -> _)
      }
      val includes = extended.collect { case (target, RawExtended("includes", _, Some(mixin), _, _, _)) =>
        IdlIncludes(target, mixin)
      }
      // Merge partial members into the existing entry. If the target doesn't exist yet
      // (a partial that arrives before its host), create a new entry treating the partial
      // as the canonical definition — the type flag tells us interface vs mixin.
      val partials = extended.collect {
        case (target, RawExtended(t, _, _, _, _, Some(members))) if (t == "interface" || t == "interface mixin") =>
          val attrs = members.collect { case RawMember("attribute", Some(n), Some(typ), _, ro, _, _, ext) =>
            IdlAttribute(n, simpleIdlType(typ).getOrElse("any"), ro.getOrElse(false), ext.exists(_.name == "Reflect"))
          }
          val ops = members.collect {
            case RawMember("operation", Some(n), Some(typ), maybeArgs, _, _, _, _) if n.nonEmpty =>
              IdlOperation(n, simpleIdlType(typ).getOrElse("any"), paramsOf(maybeArgs))
          }
          (target, t == "interface mixin", attrs, ops)
      }
      val withPartials = partials.foldLeft(interfaces) { case (acc, (target, isMixin, attrs, ops)) =>
        val existing = acc.getOrElse(target, IdlInterface(target, None, Nil, Nil, isMixin))
        acc.updated(
          target,
          existing.copy(
            attributes = existing.attributes ++ attrs,
            operations = existing.operations ++ ops,
          ),
        )
      }
      Idl(withPartials, includes, (bareCallbacks ++ interfaceCallbacks).toList, dictionaries, enums)
    }

  /** Merge interface tables AND includes statements from several idlparsed spec files into one lookup.
    *
    * The HTML element interfaces live in `html.json` while their event-interface ancestors live in `uievents.json`,
    * `pointerevents.json`, etc. Inheritance walks need them all in one map. On a name collision the earlier file wins
    * (specs rarely redefine, and dedup is harmless). `includes` statements concatenate — duplicates across specs are
    * harmless since they would re-add the same mixin attributes (already deduped at attribute walk time).
    */
  def mergeIdl(idls: Idl*): Idl =
    // Smart merge: when two specs both define the same name, concat their attributes +
    // operations rather than letting one overwrite the other. Crucial for interfaces like
    // `Document` and `Window` that have a base definition in `dom.json` / `html.json` and
    // a long tail of partial extensions in many other specs (CSSOM, fullscreen, picture-
    // in-picture, page-visibility, etc.). `inheritance` and `isMixin` come from the first
    // entry that has them — partials never declare these, so the base interface wins.
    def mergeOne(a: IdlInterface, b: IdlInterface): IdlInterface =
      IdlInterface(
        name = a.name,
        inheritance = a.inheritance.orElse(b.inheritance),
        attributes = a.attributes ++ b.attributes,
        operations = a.operations ++ b.operations,
        isMixin = a.isMixin || b.isMixin,
      )
    val mergedIfaces = idls.foldLeft(Map.empty[String, IdlInterface]) { (acc, idl) =>
      idl.interfaces.foldLeft(acc) { case (a, (k, v)) =>
        a.get(k) match
          case Some(existing) => a.updated(k, mergeOne(existing, v))
          case None           => a.updated(k, v)
      }
    }
    Idl(
      interfaces = mergedIfaces,
      includes = idls.flatMap(_.includes).toList,
      callbacks = idls.flatMap(_.callbacks).toList,
      dictionaries = idls.flatMap(_.dictionaries).toList,
      enums = idls.flatMap(_.enums).toList,
    )
  end mergeIdl

end Webref

/** Raised when vendored webref JSON fails to decode — treated as a build-stopping bug, since the data is committed and
  * pinned, not fetched at runtime.
  */
final case class WebrefParseError(source: String, detail: String)
    extends RuntimeException(s"Failed to parse webref $source data: $detail")
