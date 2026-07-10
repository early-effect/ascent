package ascent.domgen.css

/** Resolves keyword-method collisions when an object/trait mixes in multiple traits that contribute keywords.
  *
  * Two collision shapes arise once value-type traits compose (`object display with DisplayInside with DisplayListitem`,
  * `trait DisplayListitem extends DS with DisplayOutside`):
  *   1. An OWN inline keyword duplicates one a mixed-in trait already provides → drop the own def (the trait wins).
  *   2. A DIAMOND — two independent mixed-in traits each DECLARE the same literal keyword (`flow` in both
  *      `<display-inside>` and `<display-listitem>`) → Scala needs an explicit `override def` on the concrete site to
  *      resolve the inherited ambiguity. The rendered value is identical (`apply("flow")`), so the override is safe.
  *
  * The resolver tracks each keyword's DECLARING trait (where its `def` originates) through the composition graph, so a
  * keyword inherited via a single chain (`B extends A`, both "have" it) is NOT a diamond — only genuinely independent
  * declarations conflict.
  */
object KeywordResolution:

  /** For one trait: the keywords it DECLARES in its own body, by Scala name → dom name. */
  type OwnKeywords = Map[String, String]

  /** Hand-written foundation + ADT-mixin traits' own keyword members (the `def <kw>: Declaration` they declare in
    * `StylesFoundation`). Included so own-inline dedup + diamond detection works against them too — a property that
    * mixes `Positionish` (which declares `center`) AND has an inline `center` from its grammar must drop the inline
    * one. Keep in sync with the `*ish` traits in `StylesFoundation`. (Constructor-style members like `Transformish`'s
    * `translateY` aren't CSS keywords, so they never collide with a generated keyword `def` and are omitted.)
    */
  private val foundationOwn: Map[String, OwnKeywords] = Map(
    "Auto"              -> Map("auto" -> "auto"),
    "None"              -> Map("none" -> "none"),
    "Normal"            -> Map("normal" -> "normal"),
    "TimingFunctionish" -> Map(
      "ease"      -> "ease",
      "linear"    -> "linear",
      "easeIn"    -> "ease-in",
      "easeOut"   -> "ease-out",
      "easeInOut" -> "ease-in-out",
      "stepStart" -> "step-start",
      "stepEnd"   -> "step-end",
    ),
    "Positionish" -> Map(
      "center"      -> "center",
      "topLeft"     -> "top-left",
      "topRight"    -> "top-right",
      "bottomLeft"  -> "bottom-left",
      "bottomRight" -> "bottom-right",
    ),
  )

  /** A keyword reachable at a use site: its dom name + the set of traits that independently DECLARE it (size ≥ 2 ⇒
    * diamond).
    */
  final case class Reach(domName: String, declaredBy: Set[String])

  /** Build, for every trait (generated + foundation), the transitive closure of reachable keywords with their
    * declaring-trait provenance. `catalog` carries each generated trait's own keywords + the traits it `extends`.
    */
  def closures(catalog: List[ValueTrait]): Map[String, Map[String, Reach]] =
    val ownByTrait: Map[String, OwnKeywords] =
      foundationOwn ++ catalog.map(t => t.traitName -> t.keywords.map(k => k.scalaName -> k.domName).toMap)
    val extendsByTrait: Map[String, List[String]] =
      catalog.map(t => t.traitName -> t.extendsTraits).toMap

    val memo = scala.collection.mutable.HashMap.empty[String, Map[String, Reach]]
    def closureOf(traitName: String, stack: Set[String]): Map[String, Reach] =
      memo.get(traitName) match
        case Some(c)    => c
        case scala.None =>
          if stack.contains(traitName) then Map.empty // cycle guard (shouldn't happen, but be safe)
          else
            val own = ownByTrait.getOrElse(traitName, Map.empty).map { case (scalaName, domName) =>
              scalaName -> Reach(domName, Set(traitName))
            }
            val inherited = extendsByTrait.getOrElse(traitName, Nil).flatMap(p => closureOf(p, stack + traitName))
            // Merge: union declaring-trait provenance for the same Scala name.
            val merged = scala.collection.mutable.HashMap.empty[String, Reach]
            (own.toList ++ inherited).foreach { case (name, r) =>
              merged.updateWith(name) {
                case scala.None     => Some(r)
                case Some(existing) => Some(Reach(existing.domName, existing.declaredBy ++ r.declaredBy))
              }
            }
            val result = merged.toMap
            memo(traitName) = result
            result
    // Compute closures for every trait we know about — generated (catalog) AND hand-written (foundationOwn) — so a
    // property mixing `Positionish`/`TimingFunctionish` resolves its inherited keywords too.
    ownByTrait.keys.foreach(t => closureOf(t, Set.empty))
    memo.toMap
  end closures

  /** The result of resolving a use site's keyword body: own keywords to keep as plain defs, plus diamond keywords to
    * emit as `override def`.
    */
  final case class Resolved(kept: List[KeywordMethod], overrides: List[KeywordMethod])

  /** Resolve the keyword body for an object/trait that DECLARES `ownKeywords` and mixes in `mixedTraits`.
    *
    *   - An own keyword already provided by ANY mixed-in trait is dropped (the trait provides it).
    *   - A keyword DECLARED by ≥2 distinct traits among the mixed-in set's closures is a diamond → emit one
    *     `override def`. (Own keywords that also diamond are folded into the override set.)
    */
  def resolve(
      ownKeywords: List[KeywordMethod],
      mixedTraits: List[String],
      closures: Map[String, Map[String, Reach]],
  ): Resolved =
    // Aggregate reachable keywords across all mixed-in traits, unioning provenance.
    val reach = scala.collection.mutable.HashMap.empty[String, Reach]
    mixedTraits.foreach { t =>
      closures.getOrElse(t, Map.empty).foreach { case (name, r) =>
        reach.updateWith(name) {
          case scala.None     => Some(r)
          case Some(existing) => Some(Reach(existing.domName, existing.declaredBy ++ r.declaredBy))
        }
      }
    }
    val diamonds = reach.collect { case (name, r) if r.declaredBy.size >= 2 => name -> r }.toMap
    // Own keywords: drop those any mixed trait already provides; the rest stay as plain defs.
    val kept = ownKeywords.filterNot(k => reach.contains(k.scalaName))
    // Diamonds get one override def each (deterministic order by Scala name).
    val overrides = diamonds.toList.sortBy(_._1).map { case (name, r) => KeywordMethod(name, r.domName, scala.None) }
    Resolved(kept, overrides)
  end resolve
end KeywordResolution
