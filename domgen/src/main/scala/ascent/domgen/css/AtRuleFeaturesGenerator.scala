package ascent.domgen.css

import ascent.domgen.Renderer

/** Configuration for one at-rule's feature generator pass. The shape of `@media`, `@container`, etc. is identical —
  * parenthesized condition with typed features composed via `and`/`or`/`not`. The differences are entirely the names:
  * which at-rule to filter for, which Scala trait/condition/foundation types to emit. This data class captures those
  * differences so the generator pipeline stays a single implementation.
  *
  * @param atRuleName
  *   the webref at-rule name (`@media`, `@container`)
  * @param featureTraitName
  *   the generated Scala trait name (`MediaFeatures`, `ContainerFeatures`)
  * @param foundationObject
  *   the Scala object holding the foundation traits and `*F` base class (`MediaFoundation`, `ContainerFoundation`)
  * @param featureBase
  *   the foundation base class each feature object extends (`MF` for media, `CF` for container)
  * @param conditionType
  *   the condition value type the keyword constants and `apply` overloads produce (`MediaCondition`,
  *   `ContainerCondition`)
  */
final case class AtRuleSpec(
    atRuleName: String,
    featureTraitName: String,
    foundationObject: String,
    featureBase: String,
    conditionType: String,
)

object AtRuleSpec:
  /** The `@media` configuration. */
  val Media: AtRuleSpec = AtRuleSpec(
    atRuleName = "@media",
    featureTraitName = "MediaFeatures",
    foundationObject = "MediaFoundation",
    featureBase = "MF",
    conditionType = "MediaCondition",
  )

  /** The `@container` configuration. */
  val Container: AtRuleSpec = AtRuleSpec(
    atRuleName = "@container",
    featureTraitName = "ContainerFeatures",
    foundationObject = "ContainerFoundation",
    featureBase = "CF",
    conditionType = "ContainerCondition",
  )
end AtRuleSpec

/** A generated feature object's shape: which foundation traits it mixes in (Length / Numeric / Ratio / Resolution) and
  * which named keyword constants it carries. The same shape feeds the `@media` and `@container` (and any future
  * query-style at-rule) renderers.
  */
final case class AtRuleFeatureDef(
    scalaName: String,
    domName: String,
    traits: List[String],
    keywords: List[AtRuleKeywordConstant],
    parseError: Option[String] = scala.None,
)

/** A bare-keyword constant on a feature object:
  * `val <scalaName>: <Condition> = <Condition>.Keyword("<feature>", "<domName>")`.
  */
final case class AtRuleKeywordConstant(scalaName: String, domName: String)

/** Generic generator for `<AtRule>Features.scala` — covers any query-style at-rule with descriptors arranged as
  * parenthesized features (`@media`, `@container`).
  *
  * Pipeline (mirrors [[CssGenerator]]):
  *   1. Filter out vendor-prefixed descriptors (`-webkit-...`).
  *   2. CamelCase each descriptor name to a Scala identifier.
  *   3. Parse the descriptor's `value` grammar string with [[CssGrammar]].
  *   4. Reduce the grammar to traits + keyword constants (analyzer-style: top-level alts).
  *   5. For every range descriptor (Length / Numeric / Ratio / Resolution), emit `min-`/ `max-` siblings — same shape,
  *      different prefix. The min/max prefix is the at-rule grammar layer that webref doesn't enumerate per descriptor.
  *   6. Render via [[AtRuleFeaturesRenderer]].
  *
  * Specific at-rule wrappers live in [[MediaFeaturesGenerator]] / [[ContainerFeaturesGenerator]] — they're thin facades
  * over this generic core that supply the [[AtRuleSpec]] config.
  */
object AtRuleFeaturesGenerator:

  /** Map descriptor type-refs to the foundation traits they imply. The trait NAMES are the same across at-rules
    * (`Length`, `Numeric`, `Ratio`, `Resolution`) — they're defined in the per-at-rule foundation object
    * (`MediaFoundation` / `ContainerFoundation`).
    */
  private val typeRefTraits: Map[String, String] = Map(
    "length"     -> "Length",
    "integer"    -> "Numeric",
    "number"     -> "Numeric",
    "ratio"      -> "Ratio",
    "resolution" -> "Resolution",
  )

  /** Range descriptors get `min-`/`max-` siblings — the at-rule grammar applies the prefix uniformly to features whose
    * descriptor accepts a numeric value. The siblings inherit the typed mixin but DROP the keyword constants.
    */
  private val rangeTraits: Set[String] = Set("Length", "Numeric", "Ratio", "Resolution")

  /** Generate the trait body for a single at-rule (one descriptor list). Used by tests. */
  def generate(atRule: WebrefCss.AtRule, spec: AtRuleSpec): String =
    AtRuleFeaturesRenderer.renderTrait(analyze(atRule.descriptors), spec)

  /** Aggregate every partial of the configured at-rule across the catalog (a single feature can be split across many
    * spec partials, e.g. `hover` defined in mediaqueries-5 with grammar AND in compat with no `value:`).
    * Last-writer-wins, but prefer entries that carry a `value:` field over partials without one — same rule as
    * [[WebrefCss.Catalog.fromSpecs]] applies to property declarations.
    */
  def generateFromCatalog(catalog: WebrefCss.Catalog, spec: AtRuleSpec): String =
    val partials = catalog.atrules.filter(_.name == spec.atRuleName)
    val byName   = scala.collection.mutable.LinkedHashMap.empty[String, WebrefCss.Property]
    partials.foreach(_.descriptors.foreach { d =>
      byName.get(d.name) match
        case scala.None     => byName(d.name) = d
        case Some(existing) =>
          (existing.value, d.value) match
            case (scala.None, _)       => byName(d.name) = d
            case (Some(_), scala.None) => ()                 // keep existing
            case (Some(_), Some(_))    => byName(d.name) = d // both have grammar: last wins
    })
    AtRuleFeaturesRenderer.renderTrait(analyze(byName.values.toList), spec)
  end generateFromCatalog

  /** Walk the descriptor list, skip vendor-prefixed entries, parse + analyze each. The `min-`/`max-` siblings are
    * appended after each range descriptor in source order so the generated diff stays readable —
    * `width / minWidth / maxWidth` cluster together.
    */
  private[css] def analyze(descriptors: List[WebrefCss.Property]): List[AtRuleFeatureDef] =
    val out = scala.collection.mutable.ListBuffer.empty[AtRuleFeatureDef]
    descriptors.foreach { d =>
      if !d.name.startsWith("-") then // skip vendor prefixes (`-webkit-...`)
        val base = toFeatureDef(d)
        out += base
        // For range descriptors, also emit min-/max- siblings WITHOUT the keyword constants.
        // (A min-/max- range is a numeric comparison; keywords like `infinite` only fit the
        // bare descriptor.)
        val isRange = base.traits.exists(rangeTraits.contains)
        if isRange then
          out += AtRuleFeatureDef(
            scalaName = "min" + capitalize(base.scalaName),
            domName = "min-" + base.domName,
            traits = base.traits,
            keywords = Nil,
          )
          out += AtRuleFeatureDef(
            scalaName = "max" + capitalize(base.scalaName),
            domName = "max-" + base.domName,
            traits = base.traits,
            keywords = Nil,
          )
        end if
    }
    out.toList
  end analyze

  private def toFeatureDef(d: WebrefCss.Property): AtRuleFeatureDef =
    val scalaName = camelCase(d.name)
    d.value match
      case scala.None =>
        // Partial without a grammar: emit a bare object (apply(String) escape hatch only).
        AtRuleFeatureDef(scalaName, d.name, Nil, Nil)
      case Some(grammar) =>
        CssGrammar.parse(grammar) match
          case Right(ast) =>
            val (traits, keywords) = analyzeGrammar(ast)
            AtRuleFeatureDef(scalaName, d.name, traits, keywords)
          case Left(err) =>
            AtRuleFeatureDef(scalaName, d.name, Nil, Nil, parseError = Some(err))
    end match
  end toFeatureDef

  private def analyzeGrammar(g: CssGrammar.Grammar): (List[String], List[AtRuleKeywordConstant]) =
    val branches = topLevelAlternatives(g)
    val traits   = scala.collection.mutable.LinkedHashSet.empty[String]
    val keywords = scala.collection.mutable.LinkedHashMap.empty[String, AtRuleKeywordConstant]
    branches.foreach {
      case CssGrammar.TypeRef(name) =>
        val baseName = name.takeWhile(c => c != ' ' && c != '[').trim
        typeRefTraits.get(baseName).foreach(traits += _)
      case CssGrammar.Keyword(name) =>
        val sName = camelCase(name)
        if !keywords.contains(sName) then keywords += (sName -> AtRuleKeywordConstant(sName, name))
      case _ => ()
    }
    (traits.toList, keywords.values.toList)
  end analyzeGrammar

  private def topLevelAlternatives(g: CssGrammar.Grammar): List[CssGrammar.Grammar] =
    g match
      case CssGrammar.OneOf(alts)             => alts.flatMap(topLevelAlternatives)
      case CssGrammar.AnyOrderOneOrMore(alts) => alts.flatMap(topLevelAlternatives)
      case CssGrammar.AllInAnyOrder(alts)     => alts.flatMap(topLevelAlternatives)
      case CssGrammar.Sequence(items)         => items.flatMap(topLevelAlternatives)
      case CssGrammar.Group(inner)            => topLevelAlternatives(inner)
      case CssGrammar.Multiplied(operand, _)  => topLevelAlternatives(operand)
      case CssGrammar.Keyword(_)              => List(g)
      case CssGrammar.TypeRef(_)              => List(g)
      case _                                  => Nil

  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s
    else parts.head + parts.tail.map(_.capitalize).mkString

  private def capitalize(s: String): String =
    if s.isEmpty then s else s.head.toUpper.toString + s.tail
end AtRuleFeaturesGenerator

/** Renders a list of [[AtRuleFeatureDef]]s into the body of one `<AtRule>Features.scala`. The trait/object/type names
  * are supplied by the [[AtRuleSpec]] so the renderer doesn't hardcode `MediaFeatures` vs `ContainerFeatures`.
  */
object AtRuleFeaturesRenderer:

  private val header =
    """// AUTO-GENERATED by ascent-domgen. Do not edit by hand — see domgen/README.md to regenerate.
      |""".stripMargin

  def renderFeature(d: AtRuleFeatureDef, spec: AtRuleSpec): String =
    val safeObj    = Renderer.safeId(d.scalaName)
    val withs      = d.traits.map(t => s"with $t").mkString(" ")
    val errComment = d.parseError match
      case scala.None => ""
      case Some(msg)  =>
        s"// could not parse: ${msg.replace('\n', ' ').replace('\r', ' ').take(160)}\n"
    val base       = spec.featureBase
    val headerLine =
      if withs.isEmpty then s"""${errComment}object $safeObj extends $base("${d.domName}")"""
      else s"""${errComment}object $safeObj extends $base("${d.domName}") $withs"""
    if d.keywords.isEmpty then headerLine
    else
      val widest = d.keywords.map(k => Renderer.safeId(k.scalaName).length).max
      val body   = d.keywords
        .map { k =>
          val sid = Renderer.safeId(k.scalaName)
          val pad = " " * (widest - sid.length)
          s"""    val $sid: ${spec.conditionType} $pad= ${spec.conditionType}.Keyword("${d.domName}", "${k.domName}")"""
        }
        .mkString("\n")
      s"""$headerLine:
         |$body""".stripMargin
    end if
  end renderFeature

  def renderTrait(defs: List[AtRuleFeatureDef], spec: AtRuleSpec): String =
    val body = defs.map(d => indent(renderFeature(d, spec), "  ")).mkString("\n\n")
    s"""$header
       |package ascent.css
       |
       |import ${spec.foundationObject}.*
       |
       |/** Generated `${spec.atRuleName}` feature catalog. Mixed in via the corresponding
       |  * entry-point object so users see the typed feature surface at the same call site
       |  * as the hand-written ${spec.foundationObject} traits.
       |  */
       |trait ${spec.featureTraitName}:
       |$body
       |""".stripMargin
  end renderTrait

  private def indent(text: String, pad: String): String =
    text
      .split('\n')
      .map { line =>
        if line.isEmpty then line else pad + line
      }
      .mkString("\n")
end AtRuleFeaturesRenderer
