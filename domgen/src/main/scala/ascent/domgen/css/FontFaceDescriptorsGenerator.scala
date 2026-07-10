package ascent.domgen.css

/** Generator for `FontFaceDescriptors.scala` — typed `@font-face` descriptor catalog. Thin facade over
  * [[DescriptorBlockGenerator]] with [[DescriptorBlockSpec.FontFace]] supplying the names + ergonomics overrides.
  *
  * Output is committed to `css/.../generated/FontFaceDescriptors.scala` and consumed by the hand-written
  * [[ascent.css.FontFace]] block.
  */
object FontFaceDescriptorsGenerator:

  def generate(atRule: WebrefCss.AtRule, existingPropertyNames: Set[String] = Set.empty): String =
    DescriptorBlockGenerator.generate(atRule, DescriptorBlockSpec.FontFace, existingPropertyNames)

  def generateFromCatalog(catalog: WebrefCss.Catalog): String =
    DescriptorBlockGenerator.generateFromCatalog(catalog, DescriptorBlockSpec.FontFace)
end FontFaceDescriptorsGenerator
