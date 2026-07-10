package ascent.squawk

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror

/** Pluggable equality used by [[Squawk]] for change-detection.
  *
  * Three tiers of instance, in priority order (most specific wins):
  *   1. an explicit `given Eq[T]` you write — always wins (it lives in `Eq`'s body / your scope, which outranks the
  *      parent-trait fallback);
  *   2. [[Eq.derived]] — a STRUCTURAL, field-by-field instance synthesised for any case class or enum via its `Mirror`.
  *      Cheap and predictable; recurses through nested products/sums;
  *   3. [[LowPriorityEq.universal]] — a `==` fallback, but GATED on `CanEqual[A, A]`. This is the safety lever: a user
  *      who opts in to `-language:strictEquality` no longer gets a silent `Eq` for types that have no sensible equality
  *      (raw function types, mismatched opaque types) — those simply fail to resolve. Ascent forces no compiler flags;
  *      the gate just rewards strictness when you choose it.
  *
  * Override when you want non-structural semantics: `given Eq[Foo] = Eq.byRef` for identity, or `Eq.by(_.version)` to
  * compare a large record by a cheap key.
  */
trait Eq[-A]:
  def eqv(a: A, b: A): Boolean

/** The lowest-priority tier. Living on a parent trait (rather than in `Eq`'s body) is what makes "a more specific
  * instance wins" a PRIORITY rule instead of a fragile specificity tie — the structural [[Eq.derived]] and any explicit
  * `given` both outrank this.
  */
sealed trait LowPriorityEq:
  /** `==` fallback, available only where multiversal equality permits (`CanEqual[A, A]`). With strictEquality off,
    * `CanEqual.canEqualAny` makes this universal as before; with it on, the gate excludes types that shouldn't be
    * compared.
    */
  given universal[A](using CanEqual[A, A]): Eq[A] = (a, b) => a == b

object Eq extends LowPriorityEq:

  /** Identity-based equality. Use `given Eq[Foo] = Eq.byRef` to compare by reference, useful when structural equality
    * is expensive and a `Foo` instance is treated as immutable.
    */
  def byRef[A <: AnyRef]: Eq[A] = (a, b) => a eq b

  /** Compare by a derived key, e.g. `Eq.by((_: Doc).version)`. */
  def by[A, K](key: A => K)(using ke: Eq[K]): Eq[A] = (a, b) => ke.eqv(key(a), key(b))

  /** Structural, Mirror-based derivation for case classes (products) and enums/sealed hierarchies (sums).
    * Field-by-field for products; same-case-then-recurse for sums. Recurses to the `Eq` of each element type, so nested
    * case classes compare structurally and a field with an explicit/override `Eq` uses it. Inline so the per-element
    * `Eq`s are resolved at the derivation site with no runtime reflection.
    */
  inline given derived[A](using m: Mirror.Of[A]): Eq[A] =
    inline m match
      case s: Mirror.SumOf[A]     => sumEq[A](s, summonCaseEqs[s.MirroredElemTypes])
      case p: Mirror.ProductOf[A] => productEq[A](summonElemEqs[p.MirroredElemTypes])

  /** Field-by-field product equality. Non-inline so its anonymous class is defined ONCE rather than duplicated at every
    * `derived` call site — only the element-`Eq` summoning is inlined.
    */
  private def productEq[A](elemEqs: Vector[Eq[?]]): Eq[A] = new Eq[A]:
    def eqv(a: A, b: A): Boolean =
      val pa = a.asInstanceOf[Product]
      val pb = b.asInstanceOf[Product]
      var i  = 0
      var ok = true
      while ok && i < elemEqs.length do
        ok = elemEqs(i).asInstanceOf[Eq[Any]].eqv(pa.productElement(i), pb.productElement(i))
        i += 1
      ok

  /** Same-case-then-recurse sum equality. Non-inline for the same reason as [[productEq]]. */
  private def sumEq[A](s: Mirror.SumOf[A], caseEqs: Vector[Eq[?]]): Eq[A] = new Eq[A]:
    def eqv(a: A, b: A): Boolean =
      val ia = s.ordinal(a)
      val ib = s.ordinal(b)
      ia == ib && caseEqs(ia).asInstanceOf[Eq[Any]].eqv(a, b)

  /** Summon an `Eq` for every element type of a product (its fields). */
  private inline def summonElemEqs[T <: Tuple]: Vector[Eq[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Vector.empty
      case _: (h *: t)   => summonInline[Eq[h]] +: summonElemEqs[t]

  /** Summon an `Eq` for every case of a sum. Each case type has its own `Mirror`, so `derived` (or an explicit
    * instance) is summoned recursively.
    */
  private inline def summonCaseEqs[T <: Tuple]: Vector[Eq[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => Vector.empty
      case _: (h *: t)   => summonInline[Eq[h]] +: summonCaseEqs[t]

end Eq
