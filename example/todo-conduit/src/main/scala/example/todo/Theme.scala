package example.todo

import ascent.*
import ascent.css.Styles.*

/** The shared design system: the synthwave palette and the one keyframe used by more than one component. Everything
  * here is genuinely cross-component; styling that belongs to a single component lives on that component (e.g.
  * [[Header.Input]], [[Footer.Filters]]).
  */
object Theme:

  val accent      = Color.hex("#ff00aa")
  val accentSoft  = accent.alpha(0.65) // the soft accent IS the accent, dimmed — derive, don't duplicate
  val accentGlow  = accent.alpha(0.35)
  val cyan        = Color.hex("#00f0ff")
  val onInk       = Color.hex("#f6e7ff")
  val onInkDim    = Color.hex("#9b88c4")
  val onInkFaint  = Color.hex("#5d4f80")
  val danger      = Color.hex("#ff5577")
  val glassFill   = Color.rgba(38, 22, 64, 0.55)
  val glassBorder = accent.alpha(0.25)
  val divider     = accent.alpha(0.12)

  /** Fade + slide-up, shared by the app shell ([[App.Shell]]) and newly-added rows ([[TodoItem.Row]]). */
  object FadeSlideIn
      extends Keyframes(
        Frame.from(
          opacity(0.0),
          transform(Transform.translateY(Length.px(8))),
        ),
        Frame.to(
          opacity(1.0),
          transform(Transform.translateY(Length.zero)),
        ),
      )

end Theme
