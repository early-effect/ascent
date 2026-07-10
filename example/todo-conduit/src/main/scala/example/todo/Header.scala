package example.todo

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*
import zio.*

/** New-todo input with two-way value binding. (The `/` focus shortcut is a document-level concern, so it lives on the
  * app shell — see [[App]] — and targets this input by its [[Input]] class.)
  */
object Header:

  /** The header bar that anchors the new-todo input. */
  object Bar
      extends CssClass(
        position.relative,
        borderBottom(Border.solid(1.px, Theme.divider)),
      )

  /** The new-todo text input. */
  object Input
      extends CssClass(
        width.pct(100),
        // border-box keeps padding inside the 100% width so the input can't overflow the card.
        boxSizing.borderBox,
        padding(22.px, 24.px, 22.px, 60.px),
        border.none,
        background(Color.transparent),
        fontSize.px(22),
        fontWeight(300),
        fontFamily.inherit,
        color(Theme.onInk),
        caretColor(Theme.accent),
        letterSpacing.px(0.3),
        Selector(PseudoClass.focus, outline.none),
        // Placeholder borrows Inter italic — Orbitron ships no italic face (would be faux-skewed).
        Selector(
          PseudoElement.placeholder,
          color(Theme.onInkFaint),
          fontSize.px(16),
          fontFamily.of(FontFamily.named("Inter"), FontFamily.systemUi, FontFamily.sansSerif),
          fontStyle.italic,
        ),
      )

  def component(ctx: Ctx[TodoApp.Model]) =
    for draft <- ctx.squawk(_.draft)
    yield E.header(
      Bar,
      Aria.role("banner"),
      E.input(
        Input,
        A.typ("text"),
        A.placeholder("What needs to be done? (press / to focus)"),
        Aria.ariaLabel("New todo (press Enter to add, / to focus)"),
        A.autofocus(true),
        A.value(draft),
        Events.onInput(e => ctx(TodoApp.Action.SetDraft(e.targetValue.getOrElse("")))),
        Events.onKeyDown(e =>
          if e.key.contains("Enter") then ctx(TodoApp.Action.CreateFromDraft)
          else ZIO.unit
        ),
      ),
    )
end Header
