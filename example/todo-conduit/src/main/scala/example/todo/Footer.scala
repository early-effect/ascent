package example.todo

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*

/** Footer: live count of active todos, three filter buttons, and clear-completed. */
object Footer:

  /** The footer bar. Referenced by [[App.Glass]]'s container query (the bar stacks vertically in a narrow card). */
  object Bar
      extends CssClass(
        color(Theme.onInkDim),
        padding(14.px, 20.px),
        fontSize.px(13),
        letterSpacing.px(0.3),
        borderTop(Border.solid(1.px, Theme.divider)),
        display.flex,
        alignItems.center,
        justifyContent.spaceBetween,
        background(Color.rgba(10, 4, 24, 0.4)),
      )

  /** The `<ul>` of filter buttons (All / Active / Completed). */
  object Filters
      extends CssClass(
        display.flex,
        margin.zero,
        padding.zero,
        listStyle.none,
        Selector(Sel.descendant(Elem.li), margin(0, 4.px)),
        Selector(
          Sel.descendant(Elem.button),
          background(Color.transparent),
          border(Border.solid(1.px, Color.transparent)),
          color(Theme.onInkDim),
          padding(5.px, 12.px),
          cursor.pointer,
          borderRadius.px(20),
          fontSize.px(12),
          textTransform.uppercase,
          letterSpacing.px(1),
          transition(
            Transition.list(
              Transition(color, Time.s(0.2)),
              Transition(borderColor, Time.s(0.2)),
              Transition(background, Time.s(0.2)),
              Transition(boxShadow, Time.s(0.2)),
            )
          ),
          Selector(PseudoClass.hover, color(Theme.onInk), border(Border.solid(1.px, Theme.glassBorder))),
        ),
      )

  /** Applied alongside a [[Filters]] button when its filter is active. */
  object FilterSelected
      extends CssClass(
        color(Theme.accent),
        border(Border.solid(1.px, Theme.accentSoft)),
        background(Theme.accent.alpha(0.08)),
        boxShadow(Shadow(Length.zero, Length.zero, Length.px(12), Theme.accentGlow)),
      )

  /** The clear-completed button. */
  object Clear
      extends CssClass(
        background(Color.transparent),
        border.none,
        color(Theme.onInkDim),
        cursor.pointer,
        fontSize.px(12),
        textTransform.uppercase,
        letterSpacing.px(1),
        padding(4.px, 8.px),
        transition(Transition(color, Time.s(0.2))),
        Selector(PseudoClass.hover, color(Theme.danger), textDecoration.underline),
      )

  def component(ctx: Ctx[TodoApp.Model]) =
    for
      todos  <- ctx.squawk(_.todos)
      filter <- ctx.squawk(_.filter)
    yield
      val activeCount   = todos.map(_.values.count(!_.completed))
      val remainingText = activeCount.map(n => s"$n item${if n == 1 then "" else "s"} left")
      val anyCompleted  = todos.map(_.values.exists(_.completed))

      E.footer(
        Bar,
        Aria.role("contentinfo"),
        // Live region: aria-atomic so the whole count re-reads on each polite announcement.
        E.span(
          Aria.role("status"),
          Aria.ariaLive("polite"),
          Aria.ariaAtomic(true),
          remainingText,
        ),
        E.ul(
          Filters,
          Aria.role("group"),
          Aria.ariaLabel("Filter todos"),
          filterButton(ctx, filter, TodoApp.Filter.All, "All", "Show all todos"),
          filterButton(ctx, filter, TodoApp.Filter.Active, "Active", "Show only active todos"),
          filterButton(ctx, filter, TodoApp.Filter.Completed, "Completed", "Show only completed todos"),
        ),
        when(anyCompleted) {
          E.button(
            Clear,
            A.typ("button"),
            // Top so the bubble clears the footer at the bottom of the glass card.
            Tooltip("Permanently delete every completed todo", Tooltip.Position.Top),
            Ev.onClick(_ => ctx(TodoApp.Action.ClearCompleted)),
            "Clear completed",
          )
        },
      )

  /** One filter button. `aria-pressed` conveys selection to screen readers, not just colour. */
  private def filterButton(
      ctx: Ctx[TodoApp.Model],
      currentFilter: Squawk[TodoApp.Filter],
      thisFilter: TodoApp.Filter,
      label: String,
      tooltipText: String,
  ) =
    val classes = currentFilter.map(f => if f == thisFilter then Set[CssClass](FilterSelected) else Set.empty[CssClass])
    val pressed = currentFilter.map(_ == thisFilter)
    E.li(
      E.button(
        classes,
        Aria.ariaPressed(pressed),
        A.typ("button"),
        // tooltipText overrides aria-label so SRs hear the description, not just the terse label.
        Tooltip(tooltipText, Tooltip.Position.Top),
        Ev.onClick(_ => ctx(TodoApp.Action.SetFilter(thisFilter))),
        label,
      )
    )
  end filterButton
end Footer
