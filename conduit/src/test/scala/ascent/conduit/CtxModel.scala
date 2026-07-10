package ascent.conduit

import _root_.conduit.*
import zio.*

/** Test fixture for [[CtxSpec]], and the only file in the Ctx test set that imports `conduit`: it plays the app's model
  * layer, exposing a `make` factory that returns an inferred conduit so the spec drives everything through `c.ctx`.
  */
object CtxModel:

  case class Item(name: String, done: Boolean) derives Optics
  case class Meta(title: String, version: Int) derives Optics

  /** Model with scalar fields, a nested record, and one of each conduit collection shape (Map / List / Vector) so the
    * spec can exercise nested-path and key/at/atVector element subscriptions.
    */
  case class Model(
      counter: Int,
      label: String,
      meta: Meta = Meta("untitled", 0),
      items: Map[String, Item] = Map.empty,
      queue: List[Int] = Nil,
      log: Vector[String] = Vector.empty,
  ) derives Optics

  enum Op extends Action:
    case IncCounter(by: Int)
    case SetLabel(text: String)
    case SetTitle(text: String)
    case PutItem(id: String, item: Item)
    case RemoveItem(id: String)
    case PushQueue(n: Int)
    case AppendLog(line: String)

  private val M = Optics[Model]

  private val handler: ActionHandler[Model, Model, Nothing] =
    handle[Model, Model, Nothing](M):
      case Op.IncCounter(by)    => focus(_.counter)(update(_ + by))
      case Op.SetLabel(t)       => focus(_.label)(updated(t))
      case Op.SetTitle(t)       => focus(_.meta.title)(updated(t))
      case Op.PutItem(id, item) => focus(_.items)(update(_ + (id -> item)))
      case Op.RemoveItem(id)    => focus(_.items)(update(_ - id))
      case Op.PushQueue(n)      => focus(_.queue)(update(_ :+ n))
      case Op.AppendLog(line)   => focus(_.log)(update(_ :+ line))

  /** A fresh conduit seeded at `counter = 0, label = "alpha"` (collections empty). The return type is inferred so
    * callers never write the `Conduit` type.
    */
  def make: UIO[Conduit[Model, Nothing]] =
    Conduit(Model(counter = 0, label = "alpha"))(handler)
end CtxModel
