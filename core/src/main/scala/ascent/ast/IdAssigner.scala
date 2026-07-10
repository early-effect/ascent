package ascent.ast

import zio.*

/** How the runtime turns a hash into a session-stable id.
  *
  *   - [[IdMode.Hash]] — pure hash, no bookkeeping, no collision handling. Cheaper, but two structurally-distinct ASTs
  *     that hash to the same Long get the same id. The rare-but- real risk; opt in if you'd rather pay no registry tax.
  *   - [[IdMode.HashWithRegistry]] — DEFAULT. The assigner keeps a `hash → AST` map. On a collision (two distinct ASTs
  *     hash to the same Long), the assigner allocates a tiebreaker (`hash + 1`, `hash + 2`, ...) so each AST keeps a
  *     unique id. Tiny overhead per `assign` call; pays off in production safety.
  */
enum IdMode derives CanEqual:
  case Hash
  case HashWithRegistry

/** Maps an AST node to its id within a Mount session.
  *
  * For [[IdMode.HashWithRegistry]] (the default), this also enforces uniqueness via tiebreakers. Two different ASTs
  * never share an id within one [[IdAssigner]]'s lifetime, even if they hash-collide.
  *
  * Idempotent under repeat assignment: `assign(x)` returns the same id every time for the same `x` (case-class
  * equality), regardless of how many other ASTs have been assigned in between.
  */
trait IdAssigner:
  def assign(ui: UI[?]): UIO[Long]

object IdAssigner:

  /** Build an assigner using [[AstId.compute]] as the hash. */
  def make(mode: IdMode): UIO[IdAssigner] =
    makeWith(mode, AstId.compute)

  /** Build an assigner with a custom hash function. Used by tests to inject collisions. */
  def makeWith(mode: IdMode, hash: UI[?] => Long): UIO[IdAssigner] = mode match
    case IdMode.Hash =>
      ZIO.succeed(new IdAssigner:
        def assign(ui: UI[?]): UIO[Long] = ZIO.succeed(hash(ui)))

    case IdMode.HashWithRegistry =>
      // Two parallel maps:
      //   * astToId: case-class-equality on UI -> the id we previously gave it (idempotent)
      //   * idToAst: the inverse, so we can detect "is this id already taken by a DIFFERENT AST?"
      // Both updated together inside a single Ref atomic update so concurrent assigns can't
      // race in to allocate two tiebreakers for the same collision.
      Ref.make(Registry()).map { ref =>
        new IdAssigner:
          def assign(ui: UI[?]): UIO[Long] = ref.modify(_.assign(ui, hash(ui)))
      }

  /** Internal map state. Pure value; the `assign` method returns the new state plus the id to use, ready to plug into
    * `Ref#modify`.
    */
  private final case class Registry(
      astToId: Map[UI[?], Long] = Map.empty,
      idToAst: Map[Long, UI[?]] = Map.empty,
  ):
    def assign(ui: UI[?], hash: Long): (Long, Registry) =
      astToId.get(ui) match
        // Already assigned: idempotent, return the same id, state unchanged.
        case Some(id) =>
          (id, this)
        case None =>
          // First time we've seen this AST. Try the hash itself; if it's free, take it.
          // Otherwise probe `hash+1`, `hash+2`, ... for an unused tiebreaker. The probe is
          // bounded in practice by the number of registered colliders for this hash, which
          // is essentially zero outside of synthetic tests.
          var candidate = hash
          while idToAst.contains(candidate) do candidate += 1L
          val nextRegistry = copy(
            astToId = astToId.updated(ui, candidate),
            idToAst = idToAst.updated(candidate, ui),
          )
          (candidate, nextRegistry)
  end Registry
end IdAssigner
