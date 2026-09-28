package ascent.domgen

/** An operation whose result type the spec's prose fixes by an argument's value, which WebIDL cannot say. HTML's
  * `getContext("2d")` answers a `CanvasRenderingContext2D`; its IDL answers the whole `RenderingContext?` union. Each
  * replaces the IDL operation of that name on its owner with a raw native member and `Option` accessors keyed by a
  * hand-written type in `ascent.dom`, so the argument pins the result and no caller narrows a union.
  *
  * @param raw
  *   the native member, indented for the class body
  * @param accessors
  *   `extension (self: owner)` members, unindented, calling the raw one
  */
final case class SpecTypedOp(owner: String, name: String, raw: String, accessors: List[String])

object SpecTypedOp:

  /** HTML §4.12.5: the context id decides the context type and its options dictionary (`CanvasContextId`). */
  val canvasGetContext: SpecTypedOp = SpecTypedOp(
    owner = "HTMLCanvasElement",
    name = "getContext",
    raw = """  @JSName("getContext")
            |  def getContextOrNull[C, O](contextId: CanvasContextId[C, O], options: O = js.native): C | Null = js.native""".stripMargin,
    accessors = List(
      "def getContext[C, O](contextId: CanvasContextId[C, O]): Option[C] = nullable(self.getContextOrNull(contextId))",
      "def getContext[C, O](contextId: CanvasContextId[C, O], options: O): Option[C] =",
      "  nullable(self.getContextOrNull(contextId, options))",
    ),
  )

  val all: List[SpecTypedOp] = List(canvasGetContext)

  def of(owner: String): List[SpecTypedOp] = all.filter(_.owner == owner)

  def replaces(owner: String, name: String): Boolean = all.exists(op => op.owner == owner && op.name == name)
end SpecTypedOp
