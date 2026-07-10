package ascent.css

import zio.*

import scala.collection.immutable.VectorMap

/** Per-render catalog of the CSS a single mounted UI references — a ZIO service, provided fresh per render by a scoped
  * [[StyleRegistry.scoped]] layer, NOT a process-global singleton.
  *
  * Why per-render: authored CSS reaches a render as data — each style primitive ([[CssClass]], [[CssScope]],
  * [[Keyframes]], [[GlobalStyle]]) contributes its blocks through an [[ascent.ast.Attr.Style]] carried on the element
  * it's applied to, and the mount engine [[record]]s those into THIS render's registry as it walks the tree. So a
  * render's stylesheet is exactly the styles its own subtree references — two renders (e.g. a web server serving
  * different UIs) never share a catalog, and there is no shared mutable state to reset between tests.
  *
  * A block is keyed by its [[StyleSink]] key (a class name, `keyframes-<name>`, a scope id). Recording is
  * idempotent-by-key: a class applied to ten elements records ten times but flushes ONE `<style>`, and the key's first
  * value wins (keys are content-derived, so re-recording carries identical CSS). [[record]] flushes each newly-seen
  * block straight to the render's sink DURING the walk, so nodes mount already styled and a style first touched inside
  * a `when`/`forEach` body built mid-render still flushes. [[snapshot]] returns everything recorded, in order — the
  * source for SSR CSS collection ([[ascent.html]]) and for assertions.
  */
trait StyleRegistry:

  /** Record `blocks` into this render's catalog and flush the newly-seen ones (key not recorded before) to the sink, in
    * order. Duplicate keys — within the batch or across calls — flush at most once. A pure no-op for keys already seen.
    */
  def record(blocks: Iterable[(String, String)]): UIO[Unit]

  /** Every block recorded so far, in registration order — for SSR collection or test assertions. */
  def snapshot: UIO[Vector[(String, String)]]

end StyleRegistry

object StyleRegistry:

  private final class Impl(entries: Ref[VectorMap[String, String]], sink: StyleSink) extends StyleRegistry:
    def record(blocks: Iterable[(String, String)]): UIO[Unit] =
      entries
        .modify { seen =>
          // Fold from the CURRENT map each evaluation (Ref.modify may re-run under contention), so this stays a pure
          // function of `seen`: collect only keys not already present, preserving insertion order and de-duping the batch.
          val fresh = Vector.newBuilder[(String, String)]
          var acc   = seen
          blocks.foreach { case kv @ (k, v) =>
            if !acc.contains(k) then
              acc = acc.updated(k, v)
              fresh += kv
          }
          (fresh.result(), acc)
        }
        .flatMap(fresh => ZIO.foreachDiscard(fresh)((k, v) => sink.append(k, v)))

    def snapshot: UIO[Vector[(String, String)]] = entries.get.map(_.toVector)
  end Impl

  /** A fresh registry writing to `sink`. Handy for unit tests that record directly and assert via [[snapshot]] or the
    * sink; production code provides the [[scoped]] layer instead.
    */
  def make(sink: StyleSink): UIO[StyleRegistry] =
    Ref.make(VectorMap.empty[String, String]).map(Impl(_, sink))

  /** A scoped layer yielding a FRESH, isolated registry over `sink`. Provide one per render
    * (`Mount.mount(...).provideSomeLayer(StyleRegistry.scoped(sink))`): isolation between renders comes from each
    * getting its own instance, so two renders never share a catalog. Scoped (not eager) so its lifetime is bounded by
    * the providing scope and a sink that owns a resource can hang finalizers off it; the in-memory catalog itself needs
    * no teardown — it dies with the mount's closures. Live flushing happens eagerly during the walk, not at scope
    * close.
    */
  def scoped(sink: StyleSink): ZLayer[Any, Nothing, StyleRegistry] =
    ZLayer.scoped(make(sink))

end StyleRegistry
