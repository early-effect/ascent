package example.todo

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*
import zio.*

/** A single todo row, bound reactively to a per-item `Squawk[Todo]`. [[TodoList]]'s `forEach` builds the `<li>` once
  * per key; field changes flow through this row's own boundaries (class, `checked`, label text, the editing `when`).
  */
object TodoItem:

  /** A short pulse when an interaction lands (e.g. checking a todo). */
  object Pulse
      extends Keyframes(
        Frame.from(transform(Transform.scale(1))),
        Frame.pct(45)(transform(Transform.scale(1.18))),
        Frame.to(transform(Transform.scale(1))),
      )

  /** A short accent-glow flash on the row when a todo is completed. */
  object RowFlash
      extends Keyframes(
        Frame.from(background(Theme.accent.alpha(0.18))),
        Frame.to(background(Theme.accent.alpha(0.0))),
      )

  /** The `<li>` row: the checkbox, the label, and the destroy button. */
  object Row
      extends CssClass(
        position.relative,
        fontSize.px(18),
        borderBottom(Border.solid(1.px, Theme.divider)),
        display.flex,
        alignItems.center,
        // Right padding keeps the destroy button clear of the rounded card edge.
        padding(0, 20.px, 0, 0),
        minHeight.px(60),
        transition(Transition(background, Time.s(0.2))),
        Theme.FadeSlideIn.use(Time.s(0.35), TimingFunction.easeOut, fill = Some(SingleAnimationFillMode.Both)),
        Selector(PseudoClass.hover, background(Theme.accent.alpha(0.04))),
        Selector(PseudoClass.lastChild, borderBottom.none),
        Selector(
          Sel.descendant(Cls("toggle")),
          flex(0, 0, Length.auto),
          width.px(22),
          height.px(22),
          margin(0, 14.px, 0, 20.px),
          cursor.pointer,
          accentColor(Theme.accent),
          transition(Transition(transform, Time.s(0.2))),
          Selector(PseudoClass.hover, transform(Transform.scale(1.15))),
          Selector(PseudoClass.checked, Pulse.use(Time.s(0.4))),
        ),
        Selector(
          Sel.descendant(Elem.label),
          flex(1, 1, Length.auto),
          padding(18.px, 12.px),
          wordBreak.breakWord,
          cursor.pointer,
          color(Theme.onInk),
          transition(Transition(color, Time.s(0.3))),
        ),
        Selector(
          Sel.descendant(Cls("destroy")),
          flex(0, 0, Length.auto),
          width.px(36),
          height.px(36),
          margin(0, 0, 0, 8.px),
          fontSize.px(22),
          lineHeight(1),
          color(Theme.onInkFaint),
          background(Color.transparent),
          border.none,
          borderRadius.px(50),
          cursor.pointer,
          opacity(0.0),
          transition(
            Transition.list(
              Transition(opacity, Time.s(0.2)),
              Transition(color, Time.s(0.2)),
              Transition(transform, Time.s(0.2)),
              Transition(background, Time.s(0.2)),
            )
          ),
          Selector(
            PseudoClass.hover,
            color(Theme.danger),
            background(Theme.danger.alpha(0.12)),
            transform(Transform.list(Transform.rotate(Angle.deg(90)), Transform.scale(1.1))),
          ),
        ),
        Selector(PseudoClass.hover.descendant(Cls("destroy")), opacity(1.0)),
        Selector(PseudoClass.focusWithin.descendant(Cls("destroy")), opacity(1.0)),
        // Keep the destroy button visible while it itself holds keyboard focus.
        Selector(Sel.descendant(Cls("destroy").pseudoClass(PseudoClass.focusVisible)), opacity(1.0)),
        Selector(Sel.attr("draggable", AttrOp.Eq, "true"), cursor.grab),
      )

  /** Applied alongside [[Row]] when a todo is completed. */
  object Completed
      extends CssClass(
        RowFlash.use(Time.s(0.5), TimingFunction.easeOut),
        Selector(
          Sel.descendant(Elem.label),
          color(Theme.onInkDim),
          textDecoration.lineThrough,
          textDecorationColor(Theme.accentSoft),
          textDecorationThickness.px(1.5),
          transition(Transition(color, Time.s(0.3))),
        ),
      )

  /** The inline edit input shown while a row is being renamed. */
  object EditInput
      extends CssClass(
        flex(1, 1, Length.auto),
        padding(14.px, 18.px),
        margin(8.px, 16.px),
        fontSize.px(18),
        color(Theme.onInk),
        background(Color.rgba(0, 0, 0, 0.35)),
        fontFamily.inherit,
        border(Border.solid(1.px, Theme.accentSoft)),
        borderRadius.px(8),
        boxShadow(Shadow(Length.zero, Length.zero, Length.zero, Length.px(3), Theme.accentGlow)),
        outline.none,
        caretColor(Theme.accent),
      )

  /** The row being dragged: dimmed, with the move cursor. */
  object Dragging
      extends CssClass(
        opacity(0.4),
        cursor.grabbing,
      )

  /** The drop target: a top-border marks where the dragged row will land (inserted before it). */
  object DropTarget
      extends CssClass(
        boxShadow(Shadow.inset(Length.zero, Length.px(3), Length.zero, Length.zero, Theme.accent)),
        background(Theme.accent.alpha(0.06)),
      )

  /** @param draggable
    *   rows are only draggable on the All filter (reordering a filtered subset is ambiguous), so [[TodoList]] derives
    *   this from the model.
    */
  def render(ctx: Ctx[TodoApp.Model])(id: String, item: Squawk[TodoApp.Todo], draggable: Squawk[Boolean]): UI[Any] =
    // Reactive class SET: the mount engine toggles the tokens AND injects each class's CSS — no className strings, no
    // separate `.contribute`.
    val classes: Squawk[Set[CssClass]] = item.map(t => if t.completed then Set(Row, Completed) else Set(Row))

    val textValue: Squawk[String]     = item.map(_.text)
    val editingValue: Squawk[Boolean] = item.map(_.editing)

    // Announce the toggle action and which todo, so screen readers say e.g. "Mark as completed: \"buy milk\"".
    val checkboxAriaLabel: Squawk[String] =
      item.map { t =>
        val verb = if t.completed then "Mark as not completed" else "Mark as completed"
        s"""$verb: "${t.text}""""
      }

    E.li(
      classes,
      // The parent <ul> gives every direct <li> the implicit listitem role; checked state lives on the checkbox below.
      A.draggable(draggable),
      Ev.sync.onDragStart { e =>
        e.dataTransfer.setData("text/plain", id)
        e.dataTransfer.effectAllowed = "move"
        e.currentTarget.addCssClass(Dragging)
      },
      Ev.sync.onDragEnd(e => e.currentTarget.removeCssClass(Dragging)),
      Ev.sync.onDragOver { e =>
        e.preventDefault() // the browser's only cue this row accepts a drop
        e.dataTransfer.dropEffect = "move"
        e.currentTarget.addCssClass(DropTarget)
      },
      Ev.sync.onDragLeave(e => e.currentTarget.removeCssClass(DropTarget)),
      Ev.onDrop { e =>
        ZIO.succeed {
          e.preventDefault()
          e.currentTarget.removeCssClass(DropTarget)
        } *> {
          val draggedId = e.dataTransfer.getData("text/plain")
          if draggedId.nonEmpty then ctx(TodoApp.Action.MoveTodo(draggedId, id)) else ZIO.unit
        }
      },
      when(editingValue.map(!_)) {
        fragment(
          E.input(
            A.className("toggle"),
            A.typ("checkbox"),
            Aria.ariaLabel(checkboxAriaLabel),
            A.checked(item.map(_.completed)),
            Ev.onChange(_ => ctx(TodoApp.Action.ToggleCompleted(id))),
          ),
          E.label(
            Ev.onDblClick(_ => ctx(TodoApp.Action.StartEditing(id))),
            textValue,
          ),
          E.button(
            A.className("destroy"),
            A.typ("button"),
            // Positioned left — the destroy button sits at the far right of the row.
            Tooltip("Delete this todo", Tooltip.Position.Left),
            Ev.onClick(_ => ctx(TodoApp.Action.DeleteTodo(id))),
            "×",
          ),
        )
      },
      when(editingValue) { editingView(ctx, id, item) },
    )
  end render

  private def editingView(ctx: Ctx[TodoApp.Model], id: String, item: Squawk[TodoApp.Todo]): UI[Any] =
    val draftRef                    = scala.collection.mutable.StringBuilder()
    val initialText: Squawk[String] = item.map(_.text)

    E.input(
      EditInput,
      A.typ("text"),
      Aria.ariaLabel("Edit todo (Enter to save, Esc to cancel)"),
      A.autofocus(true),
      A.value(initialText),
      Events.onInput(e =>
        zio.ZIO.succeed {
          draftRef.setLength(0)
          draftRef.append(e.targetValue.getOrElse(""))
        }
      ),
      Events.onBlur(_ => ctx(TodoApp.Action.CommitEdit(id, draftRef.toString))),
      Events.onKeyDown(e =>
        e.key match
          case Some("Enter")  => ctx(TodoApp.Action.CommitEdit(id, draftRef.toString))
          case Some("Escape") => ctx(TodoApp.Action.CancelEditing(id))
          case _              => zio.ZIO.unit
      ),
    )
  end editingView

end TodoItem
