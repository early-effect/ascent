package ascent.domgen.css

/** Generator for `MediaFeatures.scala` — the typed `@media` feature catalog. Thin facade over
  * [[AtRuleFeaturesGenerator]] with [[AtRuleSpec.Media]] supplying the names. See the generic core for the pipeline;
  * this file just pins the at-rule name.
  *
  * Output is committed to `css/.../generated/MediaFeatures.scala` and the hand-written [[ascent.css.Media]] entry-point
  * object mixes it in.
  */
object MediaFeaturesGenerator:

  /** Generate the trait body for a single `@media` at-rule. Used by tests; the real driver is [[generateFromCatalog]].
    */
  def generate(atRule: WebrefCss.AtRule): String =
    AtRuleFeaturesGenerator.generate(atRule, AtRuleSpec.Media)

  /** Aggregate every `@media` partial across the catalog and emit the trait. */
  def generateFromCatalog(catalog: WebrefCss.Catalog): String =
    AtRuleFeaturesGenerator.generateFromCatalog(catalog, AtRuleSpec.Media)
end MediaFeaturesGenerator
