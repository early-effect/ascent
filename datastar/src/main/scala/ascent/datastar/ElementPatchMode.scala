package ascent.datastar

/** How a `datastar-patch-elements` event applies its HTML to the targeted element(s). Mirrors the datastar protocol's
  * modes (and the SDK's `ElementPatchMode`). The wire token (`render`) is the lowercase name carried on a
  * `mode <token>` data line; `Outer` is the protocol default and is omitted on the wire.
  */
enum ElementPatchMode(val render: String) derives CanEqual:
  case Outer   extends ElementPatchMode("outer")
  case Inner   extends ElementPatchMode("inner")
  case Replace extends ElementPatchMode("replace")
  case Append  extends ElementPatchMode("append")
  case Prepend extends ElementPatchMode("prepend")
  case Before  extends ElementPatchMode("before")
  case After   extends ElementPatchMode("after")
  case Remove  extends ElementPatchMode("remove")

object ElementPatchMode:
  /** The protocol default when no `mode` line is present. */
  val default: ElementPatchMode = Outer

  /** Parse a wire token back to a mode; `None` for an unrecognised token. */
  def fromWire(token: String): Option[ElementPatchMode] =
    values.find(_.render == token)
end ElementPatchMode
