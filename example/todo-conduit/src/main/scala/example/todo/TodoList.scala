package example.todo

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*

/** The main todo list area: a toggle-all button and the filtered `<ul>` of todo rows. */
object TodoList:

  /** The `<section>` wrapping the toggle-all button and the list. */
  object Section
      extends CssClass(
        position.relative
      )

  /** The toggle-all button (chevron + label) above the list. */
  object ToggleAll
      extends CssClass(
        display.flex,
        alignItems.center,
        gap.px(8),
        width.pct(100),
        padding(8.px, 18.px, 8.px, 12.px),
        border.none,
        borderBottom(Border.solid(1.px, Theme.divider)),
        background(Color.transparent),
        textAlign.left,
        fontSize.px(13),
        fontWeight(500),
        textTransform.uppercase,
        letterSpacing.px(2),
        color(Theme.onInkDim),
        cursor.pointer,
        transition(
          Transition.list(
            Transition(color, Time.s(0.2)),
            Transition(background, Time.s(0.2)),
          )
        ),
        Selector(PseudoClass.hover, color(Theme.cyan), background(Theme.cyan.alpha(0.04))),
        Selector(Sel.descendant(Elem.canvas), display.inlineBlock, verticalAlign.middle),
      )

  /** The `<ul>` holding the todo rows. */
  object Items
      extends CssClass(
        margin.zero,
        padding.zero,
        listStyle.none,
      )

  def component(ctx: Ctx[TodoApp.Model]) =
    for
      modelSquawk <- ctx.model
      visible = modelSquawk.map(TodoApp.visible(_).toSeq)
      // Reorder is only meaningful on the All filter — a filtered subset is ambiguous.
      reorderable = modelSquawk.map(_.filter == TodoApp.Filter.All)
    yield E.section(
      Section,
      Aria.role("main"),
      E.button(
        ToggleAll,
        A.typ("button"),
        // Bottom so the tooltip doesn't clip off the top of the page on the first row.
        Tooltip("Mark every todo as completed (or uncomplete all if all are done)", Tooltip.Position.Bottom),
        Ev.onClick(_ => ctx(TodoApp.Action.ToggleAll)),
        ChevronCanvas(width = 56, height = 28),
        " Toggle all",
      ),
      E.ul(
        Items,
        Aria.role("list"),
        Aria.ariaLabel("Todo items"),
        forEach(visible)(_.id) { t =>
          scoped {
            ctx.squawkKey(_.todos, t.id).map { itemOpt =>
              // `t` is the fallback for the tick between deletion and the row being removed.
              val itemSquawk = itemOpt.map(_.getOrElse(t))
              TodoItem.render(ctx)(t.id, itemSquawk, reorderable)
            }
          }
        },
      ),
    )
end TodoList
