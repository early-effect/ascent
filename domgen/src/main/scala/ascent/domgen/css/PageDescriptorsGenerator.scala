package ascent.domgen.css

/** Generator for `PageDescriptors.scala` — typed `@page` descriptor catalog. Thin facade over
  * [[DescriptorBlockGenerator]] with [[DescriptorBlockSpec.Page]] supplying the names.
  */
object PageDescriptorsGenerator:

  def generate(atRule: WebrefCss.AtRule, existingPropertyNames: Set[String] = Set.empty): String =
    DescriptorBlockGenerator.generate(atRule, DescriptorBlockSpec.Page, existingPropertyNames)

  def generateFromCatalog(catalog: WebrefCss.Catalog): String =
    DescriptorBlockGenerator.generateFromCatalog(catalog, DescriptorBlockSpec.Page)
end PageDescriptorsGenerator
