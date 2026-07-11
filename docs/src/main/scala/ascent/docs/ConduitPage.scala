package ascent.docs

import _root_.conduit.*
import ascent.*
import ascent.dsl.*
import specular.*
import zio.*
import zio.test.*

/** Optional conduit bridge: Ctx, squawk paths, dispatch. */
object ConduitPage extends DocSpec:

  case class Model(draft: String, count: Int) derives Optics

  enum Action extends _root_.conduit.Action:
    case SetDraft(text: String)
    case Inc

  private val M = Optics[Model]

  private val handler: ActionHandler[Model, Model, Nothing] =
    handle[Model, Model, Nothing](M):
      case Action.SetDraft(t) => focus(_.draft)(updated(t))
      case Action.Inc         => focus(_.count)(update(_ + 1))

  def doc = page("Conduit")(
    md"""
For application state, ascent ships an **optional** bridge to
[conduit](https://github.com/russwyte/conduit). Views never see conduit directly: they receive a
`Ctx[M]` and speak two verbs; `ctx.squawk(_.path)` and `ctx(action)`. Put the model and handlers
in one file; view files import only `ascent.*`.
""",
    section("Ctx verbs")(
      md"""
`ctx.squawk(_.draft)` is a reactive slice. `ctx(Action.SetDraft(…))` dispatches. Nested paths
(`ctx.squawk(_.a.b.c)`), whole-model (`ctx.model`), and one-shot reads (`ctx.read` / `ctx.current`)
are available.
""",
      exampleZIO {
        for
          c <- Conduit(Model("", 0))(handler)
          ctx = c.ctx
          _   <- ctx(Action.SetDraft("hi"))
          _   <- c.run()
          cur <- ctx.read(_.draft)
        yield cur
      }.assert(s => assertTrue(s == "hi")),
    ),
    section("View shape")(
      exampleIO {
        for
          c <- Conduit(Model("", 0))(handler)
          ctx = c.ctx
          draft <- ctx.squawk(_.draft)
        yield E.input(
          A.value(draft),
          Events.onInput(e => ctx(Action.SetDraft(e.targetValue.getOrElse("")))),
        )
      }.assert(_ => assertTrue(true))
    ),
    section("Element-scoped subscriptions")(
      md"""
`ctx.squawkKey(_.map, id)` (and `squawkAt` / `squawkAtVector`) subscribe to one collection entry.
Pair with `scoped` inside `forEach` so the listener unsubscribes when the row leaves; a churning
list never accumulates dead listeners.
""",
      example {
        E.pre("""forEach(visible)(_.id) { t =>
  scoped {
    ctx.squawkKey(_.todos, t.id).map { item =>
      TodoItem.render(ctx)(t.id, item.map(_.getOrElse(t)))
    }
  }
}""")
      }.assert(_ => assertTrue(true)),
    ),
  )
end ConduitPage
