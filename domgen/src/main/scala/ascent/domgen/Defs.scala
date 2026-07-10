package ascent.domgen

/** The intermediate, render-ready model the generator builds from parsed webref data.
  *
  * These mirror scala-dom-types' `*Def` case classes: plain data describing the API surface to emit, decoupled from
  * both the webref JSON shapes and the final Scala source strings.
  */

/** Reference to a hand-written codec in `ascent.domtypes.Codec`. The generator never invents a codec; it points at one
  * of the platform-neutral instances that already exist in `dom-types`.
  */
enum CodecRef:
  case StringAsIs
  case IntAsString
  case DoubleAsString
  case BooleanAsAttrPresence

  /** Enumerated `"true"`/`"false"` boolean (e.g. `draggable`, `spellcheck`): the attribute must carry the explicit
    * literal, NOT presence — `draggable=""` resolves to "auto" (not draggable).
    */
  case BooleanAsTrueFalse

  /** The `ascent.domtypes.Codec.<name>` value this reference renders to. */
  def render: String = s"Codec.$toString"
end CodecRef

final case class ElementDef(scalaName: String, domName: String, isVoid: Boolean, interface: String)

final case class AttrDef(scalaName: String, domName: String, codec: CodecRef)

final case class EventDef(
    scalaName: String,
    domName: String,
    eventTypeString: String,
    bubbles: Boolean,
)

/** A member of a generated event facade or interface (e.g. `clientX: Double`). `readonly` is honored by
  * [[Renderer.interfaces]] to emit `def` vs `var`; events emit `def` regardless because their `readonly attribute` flag
  * is the spec's contract.
  *
  * `reflected`/`reflectedAttrName` are meaningful only along the platform-neutral structural-trait path
  * ([[Renderer.structuralTraits]] / [[Renderer.memoryImpls]]) — they carry the webref `[Reflect]` signal (see
  * [[Webref.IdlAttribute.reflected]]) plus the HTML content-attribute name to read/write, so the in-memory DOM
  * backend's generator can auto-implement simple reflected properties with zero hand-written code. The `@js.native`
  * facade path (`Renderer.interfaces`) ignores both fields.
  */
/** `enumType` carries the WebIDL enum name (e.g. `Some("ShadowRootMode")`) when this member's raw IDL type resolves to
  * an `enum` block — `scalaType` itself stays `String` for a `@js.native` member (sound: the native declaration is
  * erased, so it must match the raw wire value), but [[Renderer.enumAccessors]] uses `enumType` to emit an ADDITIVE
  * `<name>Typed` extension accessor that converts to/from the real Scala 3 enum generated in `dom-types` — a genuine
  * conversion boundary, not an erased native binding, so it's sound to expose the strong type there.
  */
final case class FacadeMember(
    name: String,
    scalaType: String,
    readonly: Boolean = true,
    reflected: Boolean = false,
    reflectedAttrName: Option[String] = None,
    enumType: Option[String] = None,
)

/** A generated `@js.native` event-interface facade and its parent in the hierarchy.
  *
  * Carries both attribute members (`FacadeMember`) AND operations (`MethodDef`) so the emitted facade is spec-faithful
  * — `e.preventDefault()`, `e.stopPropagation()`, `e.composedPath()` etc. are typed methods on the generated class, not
  * js.Dynamic fallbacks.
  */
final case class FacadeDef(
    interface: String,
    parent: Option[String],
    members: List[FacadeMember],
    methods: List[MethodDef] = Nil,
)

/** A method parameter on a generated [[MethodDef]].
  *
  * `optional` is the IDL-level flag; the renderer also factors in overload constraints (Scala 3 forbids default args on
  * multiple overloads of the same name across the entire inheritance chain) to decide whether to emit the `= js.native`
  * default.
  */
final case class ParamDef(name: String, scalaType: String, optional: Boolean = false)

/** A generated `@js.native def`: name + parameter list + Scala return type.
  *
  * Surface is intentionally narrow — Strings, primitives, generated facade types, and `js.Any` for anything we can't
  * yet resolve (unions, sequences, generics, dictionaries). Future steps add typed return shapes for dictionaries /
  * enums.
  */
final case class MethodDef(
    scalaName: String,
    domName: String,
    returnType: String,
    params: List[ParamDef],
)

/** A field on a generated [[DictionaryDef]] — type plus required flag for default-arg emission decisions.
  */
final case class DictionaryFieldDef(name: String, scalaType: String, required: Boolean)

/** A generated `@js.native trait` for a WebIDL `dictionary` block. Dictionaries are JS option-object shapes
  * (`AddEventListenerOptions`, `KeyboardEventInit`, etc.). The emitted trait extends its parent dictionary if present
  * and `js.Object` otherwise.
  *
  * Construction at the call site uses `new D { foo = "bar" }` (Scala 3 structural literal) or
  * `js.Dynamic.literal(...).asInstanceOf[D]`.
  */
final case class DictionaryDef(name: String, parent: Option[String], fields: List[DictionaryFieldDef])

/** A generated real Scala 3 `enum`, one per WebIDL `enum` block. Each entry is `(scalaName -> domLiteral)` — the
  * renderer emits `enum <name>(val domValue: String) { case <scalaName> extends <name>("<domLiteral>") ... }` plus a
  * `fromDom(String): Option[<name>]` companion lookup, into `dom-types` (platform-neutral: jvm/js/native, no scalajs
  * dependency). This is a REAL enum type, not a stringly-typed stand-in — sound because [[Renderer.enumAccessors]]'s
  * conversion sits at a genuine boundary (the JS adapter / in-memory backend actually convert), unlike a `@js.native`
  * member, which is erased and must keep its native type as the raw wire representation (`String`).
  */
final case class EnumDef(name: String, values: List[(String, String)])

/** A generated `@js.native` interface that's NOT an HTML element (those go in `Elements.scala` + per-element
  * `Attrs.scala`) and NOT an event facade (those go in `Facades.scala`). Every other interface in the IDL — `Document`,
  * `Window`, `Performance`, `CanvasRenderingContext2D`, `Node`, etc. — gets one of these.
  *
  * Each interface emits to `dom-facade/.../generated/Interfaces.scala` as one
  * `@js.native @JSGlobal class <Name> extends <Parent>` with attribute and method members.
  *
  * `inheritedMethodNames` captures every method scalaName declared anywhere up the inheritance chain — used by the
  * renderer to decide whether a method's optional params can carry defaults. Scala 3 forbids defaults on multiple
  * overloads of the same name across an inheritance chain, so if any ancestor declares the same name, defaults are
  * stripped from this class's overloads of it.
  */
final case class InterfaceDef(
    name: String,
    parent: Option[String],
    attributes: List[FacadeMember],
    methods: List[MethodDef],
    inheritedMethodNames: Set[String] = Set.empty,
)
