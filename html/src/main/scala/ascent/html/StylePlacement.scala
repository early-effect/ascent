package ascent.html

/** Where a document render puts the CSS the tree touched.
  *
  * Both placements return that CSS on [[Html.Page]]. The choice is whether it is also written into the document.
  */
enum StylePlacement:

  /** Append one `style` element at the end of `head` when the CSS is non-empty. No empty `style` is inserted. Fails
    * with [[Html.Error.NoHead]] when the mounted tree has no `head`.
    */
  case InlineInHead

  /** Leave `head` alone. The caller places [[Html.Page.css]]. */
  case Aside
