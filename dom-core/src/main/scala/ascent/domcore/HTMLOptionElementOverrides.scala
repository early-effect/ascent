package ascent.domcore

import ascent.domcore.generated.{HTMLOptionElement, HTMLSelectElement}

/** Real controlled-option behavior for [[HTMLOptionElement]]. `selected` is genuine internal state distinct from the
  * `selected` content attribute (same story as `HTMLInputElement.checked` — the attribute only seeds `defaultSelected`,
  * never mirrors live selection). `index` is DERIVED: the option's own 0-based position among its parent `<select>`'s
  * `<option>` children, `-1` if it isn't inside a `<select>` at all (matching the real spec's answer for an orphaned
  * option).
  */
trait HTMLOptionElementOverrides:
  self: HTMLOptionElement =>

  private var _selected: Boolean = false

  def selected: Boolean            = _selected
  def selected_=(v: Boolean): Unit = _selected = v

  def index: Int =
    self.parentElement match
      case sel: HTMLSelectElement =>
        val hc = sel.getElementsByTagName("option")
        (0 until hc.length).map(hc.item).indexWhere(_.asInstanceOf[AnyRef] eq self.asInstanceOf[AnyRef])
      case _ => -1
end HTMLOptionElementOverrides
