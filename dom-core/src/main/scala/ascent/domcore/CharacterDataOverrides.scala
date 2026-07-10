package ascent.domcore

import ascent.domcore.generated.CharacterData

/** Real string-manipulation behavior for [[ascent.domcore.generated.CharacterData]]'s derived methods — all defined
  * purely in terms of `data` (a plain reflected... no, a generic mutable field already backed by the generator, see
  * `Renderer.memoryImpls`'s primitive-field policy), so each op here just reads/writes that field via ordinary `String`
  * slicing. `offset`/`count` follow the spec's own clamping rule: an out-of-range offset/count is clamped to the
  * string's actual bounds rather than throwing `IndexOutOfBoundsException` — matching a real browser's `CharacterData`
  * (which never raises for these particular ops, unlike, say, `substring` on a plain Java string).
  */
trait CharacterDataOverrides:
  self: CharacterData =>

  /** The number of code units in `data` (spec: `length` is the string's UTF-16 length). A generic zero-value field
    * would leave this a constant `0`.
    */
  def length: Int = self.data.length

  def substringData(offset: Int, count: Int): String =
    val d     = self.data
    val start = offset.max(0).min(d.length)
    val end   = (start + count.max(0)).min(d.length)
    d.substring(start, end)

  def appendData(data: String): Unit =
    self.data = self.data + data

  def insertData(offset: Int, data: String): Unit =
    val d  = self.data
    val at = offset.max(0).min(d.length)
    self.data = d.substring(0, at) + data + d.substring(at)

  def deleteData(offset: Int, count: Int): Unit =
    val d     = self.data
    val start = offset.max(0).min(d.length)
    val end   = (start + count.max(0)).min(d.length)
    self.data = d.substring(0, start) + d.substring(end)

  def replaceData(offset: Int, count: Int, data: String): Unit =
    val d     = self.data
    val start = offset.max(0).min(d.length)
    val end   = (start + count.max(0)).min(d.length)
    self.data = d.substring(0, start) + data + d.substring(end)

  def remove(): Unit =
    val p = self.parentNode
    if p != null then p.removeChild(self)
    ()
end CharacterDataOverrides
