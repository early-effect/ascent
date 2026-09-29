package ascent.domgen

/** An operation whose result type the spec's prose fixes by an argument's value, which WebIDL cannot say. HTML's
  * `getContext("2d")` answers a `CanvasRenderingContext2D`; its IDL answers the whole `RenderingContext?` union. Each
  * adds a raw native member (and, for a nullable result, `Option` accessors) keyed by a type in `ascent.dom`, so the
  * argument pins the result and no caller narrows a union or casts.
  *
  * @param replacesIdl
  *   whether the IDL operation of this name goes; `false` keeps it beside the typed one, for arguments the key cannot
  *   name (a custom element's tag)
  * @param raw
  *   the native member, indented for the class body
  * @param accessors
  *   `extension (self: owner)` members, unindented, calling the raw one
  */
final case class SpecTypedOp(
    owner: String,
    name: String,
    replacesIdl: Boolean,
    raw: String,
    accessors: List[String] = Nil,
)

object SpecTypedOp:

  /** HTML §4.12.5: the context id decides the context type and its options dictionary (`CanvasContextId`). */
  val canvasGetContext: SpecTypedOp = SpecTypedOp(
    owner = "HTMLCanvasElement",
    name = "getContext",
    replacesIdl = true,
    raw = """  @JSName("getContext")
            |  def getContextOrNull[C, O](contextId: CanvasContextId[C, O], options: O = js.native): C | Null = js.native""".stripMargin,
    accessors = List(
      "def getContext[C, O](contextId: CanvasContextId[C, O]): Option[C] = nullable(self.getContextOrNull(contextId))",
      "def getContext[C, O](contextId: CanvasContextId[C, O], options: O): Option[C] =",
      "  nullable(self.getContextOrNull(contextId, options))",
    ),
  )

  /** HTML §4.12.5.3: the same rule for an `OffscreenCanvas`, whose 2D context has its own type (`OffscreenContextId`).
    */
  val offscreenGetContext: SpecTypedOp = SpecTypedOp(
    owner = "OffscreenCanvas",
    name = "getContext",
    replacesIdl = true,
    raw = """  @JSName("getContext")
            |  def getContextOrNull[C, O](contextId: OffscreenContextId[C, O], options: O = js.native): C | Null = js.native""".stripMargin,
    accessors = List(
      "def getContext[C, O](contextId: OffscreenContextId[C, O]): Option[C] = nullable(self.getContextOrNull(contextId))",
      "def getContext[C, O](contextId: OffscreenContextId[C, O], options: O): Option[C] =",
      "  nullable(self.getContextOrNull(contextId, options))",
    ),
  )

  /** HTML's element index: an HTML tag decides the interface `createElement` answers (`HtmlTag`). */
  val documentCreateElement: SpecTypedOp = SpecTypedOp(
    owner = "Document",
    name = "createElement",
    replacesIdl = false,
    raw = "  def createElement[E <: HTMLElement](localName: HtmlTag[E]): E = js.native",
  )

  val all: List[SpecTypedOp] = List(canvasGetContext, offscreenGetContext, documentCreateElement)

  def of(owner: String): List[SpecTypedOp] = all.filter(_.owner == owner)

  def replaces(owner: String, name: String): Boolean =
    all.exists(op => op.owner == owner && op.name == name && op.replacesIdl)
end SpecTypedOp
