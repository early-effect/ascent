package example.todo

import conduit.*
import zio.*

/** The application's model, actions, and handler over the state-store backend. */
object TodoApp:

  // --- Model ---

  /** A single todo. `id` is a stable key so the row reconciler can reuse DOM nodes across reorders. */
  case class Todo(id: String, text: String, completed: Boolean, editing: Boolean = false) derives Optics

  /** Filter selections in the footer. */
  enum Filter derives CanEqual:
    case All, Active, Completed

  /** Whole app state. The `todos` map lets a row subscribe to just its entry; `order` keeps display deterministic. */
  case class Model(
      todos: Map[String, Todo] = Map.empty,
      order: Vector[String] = Vector.empty,
      draft: String = "",
      filter: Filter = Filter.All,
      nextId: Long = 1L,
  ) derives Optics

  // --- Derived selectors ---

  /** Visible todos in display order, applying the current filter. */
  def visible(m: Model): Vector[Todo] =
    val all = m.order.flatMap(m.todos.get)
    m.filter match
      case Filter.All       => all
      case Filter.Active    => all.filterNot(_.completed)
      case Filter.Completed => all.filter(_.completed)

  /** Move `dragged` to sit immediately before `target` in `order`; no-op if either id is absent or equal. */
  def reorder(order: Vector[String], dragged: String, target: String): Vector[String] =
    if dragged == target || !order.contains(dragged) || !order.contains(target) then order
    else
      val without         = order.filterNot(_ == dragged)
      val at              = without.indexOf(target)
      val (before, after) = without.splitAt(at)
      (before :+ dragged) ++ after

  // --- Actions ---

  enum Action extends _root_.conduit.Action:
    case SetDraft(text: String)
    case CreateFromDraft
    case ToggleCompleted(id: String)
    case ToggleAll
    case DeleteTodo(id: String)
    case ClearCompleted
    case SetFilter(f: Filter)
    case StartEditing(id: String)
    case CommitEdit(id: String, text: String)
    case CancelEditing(id: String)

    /** Drag-to-reorder: move `dragged` to sit immediately before `target` in display order. */
    case MoveTodo(dragged: String, target: String)
  end Action

  // --- Handler ---

  /** The full state machine over `Model`; invalid actions (e.g. empty draft) are ignored, never failed. */
  val handler: ActionHandler[Model, Model, Nothing] =
    val M = Optics[Model]
    handle[Model, Model, Nothing](M):
      case Action.SetDraft(text) =>
        focus(_.draft)(updated(text))

      case Action.CreateFromDraft =>
        update { m =>
          val trimmed = m.draft.trim
          if trimmed.isEmpty then m
          else
            val id = s"t${m.nextId}"
            m.copy(
              todos = m.todos.updated(id, Todo(id, trimmed, completed = false)),
              order = m.order :+ id,
              draft = "",
              nextId = m.nextId + 1,
            )
        }

      case Action.ToggleCompleted(id) =>
        update { m =>
          m.todos.get(id) match
            case Some(t) => m.copy(todos = m.todos.updated(id, t.copy(completed = !t.completed)))
            case None    => m
        }

      case Action.ToggleAll =>
        update { m =>
          val anyActive = m.todos.values.exists(!_.completed)
          m.copy(todos = m.todos.view.mapValues(_.copy(completed = anyActive)).toMap)
        }

      case Action.DeleteTodo(id) =>
        update(m => m.copy(todos = m.todos - id, order = m.order.filterNot(_ == id)))

      case Action.ClearCompleted =>
        update { m =>
          val keep = m.todos.filterNot { case (_, t) => t.completed }
          m.copy(todos = keep, order = m.order.filter(keep.contains))
        }

      case Action.SetFilter(f) =>
        focus(_.filter)(updated(f))

      case Action.StartEditing(id) =>
        update { m =>
          // At most one row edits at a time, so clear every other row's editing flag.
          val cleared = m.todos.view.mapValues(_.copy(editing = false)).toMap
          m.todos.get(id) match
            case Some(t) => m.copy(todos = cleared.updated(id, t.copy(editing = true)))
            case None    => m
        }

      case Action.CommitEdit(id, text) =>
        update { m =>
          val trimmed = text.trim
          if trimmed.isEmpty then m.copy(todos = m.todos - id, order = m.order.filterNot(_ == id))
          else
            m.todos.get(id) match
              case Some(t) => m.copy(todos = m.todos.updated(id, t.copy(text = trimmed, editing = false)))
              case None    => m
        }

      case Action.CancelEditing(id) =>
        update { m =>
          m.todos.get(id) match
            case Some(t) if t.editing => m.copy(todos = m.todos.updated(id, t.copy(editing = false)))
            case _                    => m
        }

      case Action.MoveTodo(dragged, target) =>
        update(m => m.copy(order = reorder(m.order, dragged, target)))
  end handler

  /** Build the backing store seeded with `seed`. */
  def make(seed: Model = Model()): UIO[Conduit[Model, Nothing]] =
    Conduit(seed)(handler)
end TodoApp
