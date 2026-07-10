package ascent.domgen.css

/** Generator for `CounterStyleDescriptors.scala` — typed `@counter-style` descriptor catalog. Thin facade over
  * [[DescriptorBlockGenerator]] with [[DescriptorBlockSpec.CounterStyle]] supplying the names.
  */
object CounterStyleDescriptorsGenerator:

  def generate(atRule: WebrefCss.AtRule, existingPropertyNames: Set[String] = Set.empty): String =
    DescriptorBlockGenerator.generate(atRule, DescriptorBlockSpec.CounterStyle, existingPropertyNames)

  def generateFromCatalog(catalog: WebrefCss.Catalog): String =
    DescriptorBlockGenerator.generateFromCatalog(catalog, DescriptorBlockSpec.CounterStyle)
end CounterStyleDescriptorsGenerator
