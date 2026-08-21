package ascent.preview.sbt

import java.net.InetSocketAddress
import java.net.ServerSocket

import neotype.*
import sjsonnew.IsoString

/** Bind address for [[AscentPreviewPlugin.autoImport.ascentPreviewPort]].
  *
  * Literals are checked at compile time: `"auto"` or an unprivileged TCP port (`1024`-`65535`). `"noo auto"` and `80`
  * do not construct.
  */
type AscentPreviewPort = AscentPreviewPort.Type

object AscentPreviewPort extends Newtype[Int | String]:
  val AutoFloor: Int = 8700

  inline def auto: AscentPreviewPort = AscentPreviewPort("auto")

  override inline def validate(input: Int | String): Boolean | String =
    input match
      case s: String =>
        if s == "auto" then true
        else """ascentPreviewPort string must be "auto""""
      case n: Int =>
        if n >= 1024 && n <= 65535 then true
        else "ascentPreviewPort must be an unprivileged port (1024-65535)"

  given AscentPreviewPortIso: IsoString[AscentPreviewPort] =
    IsoString.iso[AscentPreviewPort](
      p =>
        p.unwrap match
          case n: Int    => n.toString
          case s: String => s
      ,
      s =>
        if s == "auto" then unsafeMake("auto")
        else unsafeMake(s.toInt),
    )

  /** Concrete listen port. `"auto"` is the first free port at or above [[AutoFloor]]. */
  def resolve(port: Type): Int =
    port.unwrap match
      case "auto"    => firstOpen(AutoFloor)
      case n: Int    => n
      case s: String => sys.error(s"""ascentPreviewPort: expected "auto" or an Int, got "$s"""")

  private def firstOpen(from: Int): Int =
    (from to 65535)
      .find(isFree)
      .getOrElse(sys.error(s"ascentPreviewServe: no free port in $from-65535"))

  private def isFree(port: Int): Boolean =
    val ss = new ServerSocket()
    try
      ss.setReuseAddress(true)
      ss.bind(new InetSocketAddress(port))
      true
    catch case _: java.io.IOException => false
    finally ss.close()
end AscentPreviewPort
