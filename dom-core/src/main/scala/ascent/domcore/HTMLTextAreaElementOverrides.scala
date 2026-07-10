package ascent.domcore

import ascent.domcore.generated.HTMLTextAreaElement

/** Real controlled-textarea behavior for [[HTMLTextAreaElement]] — `value` is genuine internal state distinct from the
  * element's own text content (`defaultValue`, spec-mirrored to the textarea's child text node, seeds the INITIAL
  * `value` only — same story as `HTMLInputElement.value`/`.checked`).
  */
trait HTMLTextAreaElementOverrides:
  self: HTMLTextAreaElement =>

  private var _value: String = ""

  def value: String            = _value
  def value_=(v: String): Unit = _value = v
end HTMLTextAreaElementOverrides
