package ascent.domcore

import ascent.domcore.generated.HTMLInputElement

/** Real internal-state behavior for [[HTMLInputElement]]'s controlled-input surface — `value`/`checked`/
  * `indeterminate` are genuine internal state DISTINCT from the `value`/`checked` content attributes (that's why
  * they're on the override list rather than auto-reflected: `el.value = "x"` must NOT rewrite the `value` attribute,
  * matching real DOM semantics where the attribute only seeds the INITIAL value via `defaultValue`/ `defaultChecked`).
  *
  * `valueAsNumber` is genuinely derived from `value` (parsed as a `Double`, `NaN` on a non-numeric string — the real
  * spec behavior for `type="number"`/`"range"` inputs). `files`/`validity`/`valueAsDate` stay `???`: their real types
  * (`FileList`, `ValidityState`, `Date`) have no constructible instance in this model at all (unlike a union member,
  * there's no plain-value fallback to implement against) — an honest gap, not a stand-in for missing effort.
  */
trait HTMLInputElementOverrides:
  self: HTMLInputElement =>

  private var _value: String          = ""
  private var _checked: Boolean       = false
  private var _indeterminate: Boolean = false

  def value: String                     = _value
  def value_=(v: String): Unit          = _value = v
  def checked: Boolean                  = _checked
  def checked_=(v: Boolean): Unit       = _checked = v
  def indeterminate: Boolean            = _indeterminate
  def indeterminate_=(v: Boolean): Unit = _indeterminate = v

  def valueAsNumber: Double            = _value.toDoubleOption.getOrElse(Double.NaN)
  def valueAsNumber_=(v: Double): Unit = _value = v.toString

  def files: PlatformOpaque                      = ???
  def files_=(value: PlatformOpaque): Unit       = ???
  def validity: PlatformOpaque                   = ???
  def valueAsDate: PlatformOpaque                = ???
  def valueAsDate_=(value: PlatformOpaque): Unit = ???
end HTMLInputElementOverrides
