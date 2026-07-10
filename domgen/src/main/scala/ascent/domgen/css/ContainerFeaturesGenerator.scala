package ascent.domgen.css

/** Generator for `ContainerFeatures.scala` — the typed `@container` feature catalog. Thin facade over
  * [[AtRuleFeaturesGenerator]] with [[AtRuleSpec.Container]] supplying the names.
  *
  * Output is committed to `css/.../generated/ContainerFeatures.scala` and the hand-written [[ascent.css.Container]]
  * entry-point object mixes it in.
  */
object ContainerFeaturesGenerator:

  def generate(atRule: WebrefCss.AtRule): String =
    AtRuleFeaturesGenerator.generate(atRule, AtRuleSpec.Container)

  def generateFromCatalog(catalog: WebrefCss.Catalog): String =
    AtRuleFeaturesGenerator.generateFromCatalog(catalog, AtRuleSpec.Container)
end ContainerFeaturesGenerator
