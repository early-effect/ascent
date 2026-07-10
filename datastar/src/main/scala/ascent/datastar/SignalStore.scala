package ascent.datastar

import ascent.squawk.{Eq, Source, Squawk, sq}
import zio.*
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json

/** Named, typed, client-owned reactive `Source`s fed by addressed datastar signal patches.
  *
  * A view declares each signal it cares about with [[squawk]] — getting back a `Squawk[A]` it binds to the AST exactly
  * like any other. Incoming `patch-signals` are [[route]]d by name into the matching `Source` (RFC-7386-merged at the
  * JSON level, then decoded to `A`, then `Source.set` with the usual Eq dedup). Pure ZIO + Squawk + zio-json — no DOM —
  * so the whole routing/decoding/merge story is JVM-unit-testable.
  *
  * Design choices (see the plan):
  *   - **Statically declared:** a signal exists once a view calls `squawk`; a patch for an unregistered name is a
  *     logged no-op (it never silently stores untyped JSON).
  *   - **Decode-failure-survives:** if a merged value doesn't decode to `A`, the prior value is retained and the
  *     failure surfaced to `onError` — the connection/store keeps working.
  *   - **Delete resets to init:** an RFC-7386 `null` (a [[SignalPatch.Delete]]) resets the signal to its declared
  *     initial value, keeping the `Source[A]` total (no `Option` ceremony).
  */
final class SignalStore private (
    entriesRef: Ref[Map[String, SignalStore.Entry[?]]],
    rawRef: Ref[Map[String, Json]],
    receivedRef: Ref[Set[String]],
    onError: String => UIO[Unit],
):
  import SignalStore.Entry

  /** Declare (or look up) a named signal seeded with `init`. Idempotent per name: a second call with the same name
    * returns the existing `Squawk` (the first `init`/decoders win).
    */
  def squawk[A](name: String, init: A)(using
      dec: JsonDecoder[A],
      enc: JsonEncoder[A],
      eq: Eq[A],
  ): UIO[Squawk[A]] =
    entriesRef.get.map(_.get(name)).flatMap {
      case Some(e) => ZIO.succeed(e.source.asInstanceOf[Source[A]])
      case None    =>
        for
          src <- sq(init)(using eq)
          entry = Entry(src, init, dec, enc, eq)
          _ <- entriesRef.update(_.updated(name, entry))
          // Seed the raw JSON with the encoded init so a later PARTIAL (merge-patch) update has a
          // base to merge onto — otherwise the first partial patch can't decode on its own.
          _ <- entry.initRaw match
            case Some(j) => rawRef.update(_.updated(name, j))
            case None    => ZIO.unit
        yield src
    }

  /** Like [[squawk]] but returns the WRITABLE [[Source]] — for client-local two-way binding (an input whose value the
    * client edits and later sends to the server). The same `Source` is also the one incoming `patch-signals` drive, so
    * server pushes and client edits share one cell. Idempotent per name. Use [[squawk]] (read-only) for display
    * bindings.
    */
  def source[A](name: String, init: A)(using JsonDecoder[A], JsonEncoder[A], Eq[A]): UIO[Source[A]] =
    squawk(name, init).map(_.asInstanceOf[Source[A]])

  /** Apply one decoded signal patch. Unknown name → logged no-op; decode failure → retain prior + log. */
  def route(patch: SignalPatch): UIO[Unit] =
    patch match
      case SignalPatch.Put(name, value, onlyIfMissing) => applyPut(name, value, onlyIfMissing)
      case SignalPatch.Delete(name)                    => applyDelete(name)

  /** Route every per-name patch flattened from one `patch-signals` object. */
  def routeAll(patches: Iterable[SignalPatch]): UIO[Unit] =
    ZIO.foreachDiscard(patches)(route)

  /** Snapshot all current signal values as a JSON object — the body/query an outgoing action sends. */
  def snapshot: UIO[Json.Obj] =
    entriesRef.get.flatMap { entries =>
      ZIO
        .foreach(entries.toVector) { (name, entry) => entry.encodeCurrent.map(name -> _) }
        .map(pairs => Json.Obj(Chunk.fromIterable(pairs)))
    }

  private def applyPut(name: String, value: Json, onlyIfMissing: Boolean): UIO[Unit] =
    entriesRef.get.map(_.get(name)).flatMap {
      case None        => onError(s"signal patch for unknown signal `$name` (ignored)")
      case Some(entry) =>
        receivedRef.get.map(_.contains(name)).flatMap { alreadyReceived =>
          // `onlyIfMissing` means "don't clobber a value that already arrived" — based on whether an
          // explicit patch was received, NOT on the init-seeded merge base.
          if onlyIfMissing && alreadyReceived then ZIO.unit
          else
            rawRef.get.map(_.get(name)).flatMap { priorRaw =>
              val merged = priorRaw match
                case Some(prev) => JsonMerge.merge(prev, value)
                case None       => value
              entry.decodeAndSet(merged) match
                case Right(eff) =>
                  rawRef.update(_.updated(name, merged)) *> receivedRef.update(_ + name) *> eff
                case Left(err) => onError(s"signal `$name` decode failed: $err (value retained)")
            }
        }
    }

  private def applyDelete(name: String): UIO[Unit] =
    entriesRef.get.map(_.get(name)).flatMap {
      case None        => onError(s"delete for unknown signal `$name` (ignored)")
      case Some(entry) =>
        // Reset to the declared init: restore the init merge base, clear the received flag, and set
        // the source back to init.
        val restoreRaw = entry.initRaw match
          case Some(j) => rawRef.update(_.updated(name, j))
          case None    => rawRef.update(_ - name)
        restoreRaw *> receivedRef.update(_ - name) *> entry.resetToInit
    }
end SignalStore

object SignalStore:

  /** Per-signal bookkeeping. Captures the typed decoders/Eq at declaration time so [[route]] stays type-erased and
    * total.
    */
  private final case class Entry[A](
      source: Source[A],
      init: A,
      dec: JsonDecoder[A],
      enc: JsonEncoder[A],
      eq: Eq[A],
  ):
    /** The encoded initial value — the merge base for partial patches and the value `delete` restores. */
    val initRaw: Option[Json] = enc.toJsonAST(init).toOption

    /** Decode `merged` to `A` and return the `set` effect, or `Left(err)` if it doesn't decode. */
    def decodeAndSet(merged: Json): Either[String, UIO[Unit]] =
      dec.fromJsonAST(merged) match
        case Right(a)  => Right(source.set(a))
        case Left(err) => Left(err)

    def resetToInit: UIO[Unit] = source.set(init)

    def encodeCurrent: UIO[Json] = source.get.map(a => enc.toJsonAST(a).getOrElse(Json.Null))
  end Entry

  /** Build an empty store. `onError` receives human-readable diagnostics for ignorable conditions (unknown signal,
    * decode failure); defaults to a ZIO log warning.
    */
  def make(onError: String => UIO[Unit] = msg => ZIO.logWarning(msg)): UIO[SignalStore] =
    for
      entries  <- Ref.make(Map.empty[String, Entry[?]])
      raw      <- Ref.make(Map.empty[String, Json])
      received <- Ref.make(Set.empty[String])
    yield new SignalStore(entries, raw, received, onError)
end SignalStore
