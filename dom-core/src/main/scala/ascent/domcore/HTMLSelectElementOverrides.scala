package ascent.domcore

import ascent.domcore.generated.{HTMLCollection, HTMLOptionElement, HTMLSelectElement}

/** Real controlled-select behavior for [[HTMLSelectElement]] — `selectedIndex`/`value`/`selectedOptions` are all
  * DERIVED from the actual `<option>` descendants' own `selected` state (real DOM semantics: a `<select>` has no
  * independent selection state of its own, it's a read/write VIEW over its options), found via
  * `getElementsByTagName("option")` — the SAME real tree-walk `ElementOverrides` already implements, dispatched
  * virtually since `HTMLSelectElementMemory` mixes both traits.
  *
  * `options` stays `???`: its real type (`HTMLOptionsCollection`, a `HTMLCollection` subtype with extra
  * `add`/`remove`/`namedItem` semantics) isn't part of the portable structural surface — resolves to `PlatformOpaque`
  * with no plain-value fallback to implement against, an honest gap.
  */
trait HTMLSelectElementOverrides:
  self: HTMLSelectElement =>

  private def optionElements: Seq[HTMLOptionElement] =
    val hc = self.getElementsByTagName("option")
    (0 until hc.length).map(hc.item).map(_.asInstanceOf[HTMLOptionElement])

  def selectedIndex: Int = optionElements.indexWhere(_.selected)

  def selectedIndex_=(i: Int): Unit =
    optionElements.zipWithIndex.foreach { case (opt, idx) => opt.selected = idx == i }

  def value: String = optionElements.find(_.selected).map(_.value).getOrElse("")

  def value_=(v: String): Unit =
    optionElements.foreach(opt => opt.selected = opt.value == v)

  def selectedOptions: HTMLCollection = HTMLCollectionView(() => optionElements.filter(_.selected))

  def options: PlatformOpaque = ???
end HTMLSelectElementOverrides
