package ascent.domgen.css

/** Configuration for one descriptor-block at-rule's generator pass. The architectural shape is identical across
  * `@font-face`, `@page`, `@counter-style`, etc.: a flat list of typed descriptors at the top level (no inner rules).
  * The differences are entirely the names — which at-rule to filter for, what to call the emitted singleton object, and
  * (rarely) what hand-written ergonomics overrides to append.
  *
  * @param atRuleName
  *   the webref at-rule name (`@font-face`, `@page`, `@counter-style`)
  * @param objectName
  *   the generated Scala singleton object name (`FontFaceDescriptors`, `PageDescriptors`, `CounterStyleDescriptors`)
  * @param scaladoc
  *   a single-paragraph scaladoc placed above the generated object
  * @param appendix
  *   hand-written Scala source appended to the object body — for ergonomics overrides where webref's grammar is too
  *   rich for the analyzer's top-level-alts heuristic (`src.url(href, format)` for
  * @font-face's
  *   `<font-src-list>` grammar). Each entry is a tuple of (descriptor name to skip in the analyzer-emitted body, raw
  *   Scala source to splice in).
  */
final case class DescriptorBlockSpec(
    atRuleName: String,
    objectName: String,
    scaladoc: String,
    appendix: List[(String, String)] = Nil,
)

object DescriptorBlockSpec:
  val FontFace: DescriptorBlockSpec = DescriptorBlockSpec(
    atRuleName = "@font-face",
    objectName = "FontFaceDescriptors",
    scaladoc = """Generated `@font-face` descriptor catalog. The descriptors that are unique to
        |  * `@font-face` (`src`, `unicode-range`, the `*-override` family, `size-adjust`) — the
        |  * descriptors that ALSO exist in [[Styles]] are skipped to keep one canonical typed
        |  * builder per property name.""".stripMargin,
    appendix = List(
      "src" ->
        // The body uses simple string interpolation in the GENERATED source. We write that
        // generated source verbatim here — quotes are escaped with `+ "\""` concatenation
        // so neither this string literal NOR the renderObject interpolation churns them.
        """  /** `src: url("<href>") format("<format>")` — the most distinctive @font-face
          |    * descriptor. The webref grammar for `src` is `<font-src-list>` (a comma-separated
          |    * list of `<url> [format(<format-list>)]?` entries), too rich for the analyzer's
          |    * top-level-alts heuristic to surface. Hand-typed builder pinned here so users get
          |    * the everyday case without falling through to `apply(String)`.
          |    */
          |  object src extends DS("src"):
          |    /** Single-source builder. Quotes the href and the format per the spec. */
          |    def url(href: String, format: String): Declaration =
          |      apply("url(\"" + href + "\") format(\"" + format + "\")")
          |
          |    /** Single-source url-only builder (no format() call) — useful for system fonts
          |      * where the format is implied or not relevant.
          |      */
          |    def url(href: String): Declaration = apply("url(\"" + href + "\")")""".stripMargin,
      "unicode-range" ->
        """  /** `unicode-range: <range-token>#` — the range string is opaque (the spec grammar
          |    * is too rich for top-level alts). The bare `apply(String)` form takes a raw range
          |    * literal: `FontFaceDescriptors.unicodeRange("U+0000-00FF")`.
          |    */
          |  object unicodeRange extends DS("unicode-range")""".stripMargin,
    ),
  )

  val Page: DescriptorBlockSpec = DescriptorBlockSpec(
    atRuleName = "@page",
    objectName = "PageDescriptors",
    scaladoc = """Generated `@page` descriptor catalog (paged-media controls — printing, paged
        |  * overflow). The descriptors come from webref's `atrules[@page].descriptors[]`:
        |  * `bleed`, `marks`, `page-margin-safety`, `page-orientation`, `size`. General CSS
        |  * properties used inside `@page` (`margin`, `padding`, …) live in [[Styles]] and
        |  * are skipped here.""".stripMargin,
  )

  val CounterStyle: DescriptorBlockSpec = DescriptorBlockSpec(
    atRuleName = "@counter-style",
    objectName = "CounterStyleDescriptors",
    scaladoc = """Generated `@counter-style` descriptor catalog. The descriptors come from webref's
        |  * `atrules[@counter-style].descriptors[]`: `system`, `symbols`, `additive-symbols`,
        |  * `range`, `pad`, `prefix`, `suffix`, `negative`, `fallback`, `speak-as`. None of
        |  * these collide with general `Styles` properties, so the shared-set filter is a
        |  * no-op for this at-rule.""".stripMargin,
  )
end DescriptorBlockSpec

/** Generic generator for descriptor-block at-rules — emits one `singleton object <Name>` with one
  * `object <descriptor> extends DS("<dom-name>") with <traits>` per spec descriptor. Reuses [[CssGenerator.analyze]] /
  * [[CssRenderer.renderProperty]] (the analyzer that produces typed Length / Percent / Numeric / Auto / None / Normal
  * mixins for normal CSS properties handles descriptor grammars uniformly — descriptors ARE property-shaped).
  *
  * Specific at-rule wrappers ([[FontFaceDescriptorsGenerator]], [[PageDescriptorsGenerator]],
  * [[CounterStyleDescriptorsGenerator]]) are thin facades that supply a [[DescriptorBlockSpec]] config.
  */
object DescriptorBlockGenerator:

  /** Generate the descriptor-block source from a single at-rule. The optional `existingPropertyNames` set carries the
    * catalog of CSS PROPERTIES already emitted into [[ascent.css.Styles]] — descriptors whose name appears there are
    * SKIPPED (the user reaches them via `Styles.foo` for both rule bodies and the at-rule). When called without a
    * property catalog, no skipping happens — useful for tests.
    */
  def generate(
      atRule: WebrefCss.AtRule,
      spec: DescriptorBlockSpec,
      existingPropertyNames: Set[String] = Set.empty,
  ): String =
    renderObject(analyze(atRule.descriptors, spec, existingPropertyNames), spec, Nil)

  /** Catalog driver: aggregate every partial of the configured at-rule across the catalog AND derive the
    * shared-with-Styles set from the catalog's own property list — so descriptors that webref also lists as standalone
    * CSS properties are skipped automatically, with no hand-maintained set to drift out of sync.
    */
  def generateFromCatalog(catalog: WebrefCss.Catalog, spec: DescriptorBlockSpec): String =
    val existing     = catalog.properties.map(_.name).toSet
    val valuesByName = catalog.values.foldLeft(Map.empty[String, WebrefCss.ValueDef])((m, v) => m.updated(v.name, v))
    val valueTraits  = CssGenerator.valueTraitCatalog(valuesByName)
    val partials     = catalog.atrules.filter(_.name == spec.atRuleName)
    val byName       = scala.collection.mutable.LinkedHashMap.empty[String, WebrefCss.Property]
    partials.foreach(_.descriptors.foreach { d =>
      byName.get(d.name) match
        case scala.None     => byName(d.name) = d
        case Some(existing) =>
          (existing.value, d.value) match
            case (scala.None, _)       => byName(d.name) = d
            case (Some(_), scala.None) => ()                 // keep existing
            case (Some(_), Some(_))    => byName(d.name) = d // both have grammar: last wins
    })
    renderObject(analyze(byName.values.toList, spec, existing, valuesByName, valueTraits), spec, valueTraits)
  end generateFromCatalog

  private def analyze(
      descriptors: List[WebrefCss.Property],
      spec: DescriptorBlockSpec,
      existingPropertyNames: Set[String],
      valuesByName: Map[String, WebrefCss.ValueDef] = Map.empty,
      valueTraits: List[ValueTrait] = Nil,
  ): List[PropertyDef] =
    val handWritten = spec.appendix.map(_._1).toSet
    val filtered    = descriptors.filterNot(d => existingPropertyNames.contains(d.name) || handWritten.contains(d.name))
    // Thread the spec-wide value lookup + value-trait catalog so descriptors get the SAME treatment as properties:
    // transitive type-ref resolution, value-type-trait mixins (`<color>`→ColorLike, `<image>`→Imageish), scaladoc.
    // `propsByName` stays empty — descriptors don't use `<'property'>` refs.
    CssGenerator.analyze(filtered, valuesByName, Map.empty, valueTraits)
  end analyze

  /** Custom renderer: [[CssRenderer]] emits a `trait StylesGenerated`; we want a top-level `object <Name>` instead.
    * Same per-property rendering otherwise; ergonomics overrides (the `appendix`) are spliced into the body verbatim —
    * the appendix MUST be already-valid Scala source because we concatenate it without further interpolation.
    */
  private def renderObject(defs: List[PropertyDef], spec: DescriptorBlockSpec, valueTraits: List[ValueTrait]): String =
    val closures      = KeywordResolution.closures(valueTraits)
    val body          = defs.map(d => indent(CssRenderer.renderProperty(d, closures), "  ")).mkString("\n\n")
    val appendix      = spec.appendix.map(_._2).mkString("\n\n")
    val appendixBlock = if appendix.isEmpty then "" else "\n\n" + appendix
    val header        =
      s"""// AUTO-GENERATED by ascent-domgen. Do not edit by hand — see domgen/README.md to regenerate.
         |
         |package ascent.css
         |
         |import StylesFoundation.*
         |
         |/** ${spec.scaladoc}
         |  */
         |object ${spec.objectName} extends StylesValueTraits:
         |
         |""".stripMargin
    // Concatenate body + appendix WITHOUT interpolating either — the appendix carries raw
    // Scala source containing `$` and `"""` that must reach the generated file as-is.
    header + body + appendixBlock + "\n"
  end renderObject

  private def indent(text: String, pad: String): String =
    text
      .split('\n')
      .map { line =>
        if line.isEmpty then line else pad + line
      }
      .mkString("\n")
end DescriptorBlockGenerator
