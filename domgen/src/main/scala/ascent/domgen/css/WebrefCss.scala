package ascent.domgen.css

import ascent.domgen.WebrefParseError
import zio.*
import zio.json.*

/** Webref CSS JSON parser.
  *
  * Each `data/webref/css/<spec>.json` file has the shape
  * {{{
  *   { "spec":      { "title", "url" },
  *     "properties": [ { "name", "value", "initial?", "inherited?", ... } ],
  *     "atrules":    [ { "name", "descriptors": [ ...prop-shaped... ] } ],
  *     "values":     [ { "name", "type", "value?", "values?" } ],
  *     "selectors":  [ ... ignored by codegen ] }
  * }}}
  *
  * The parser ingests one file into a [[WebrefCss.Spec]]; multiple specs aggregate into a [[WebrefCss.Catalog]].
  * Unknown JSON fields fall away (zio-json is lenient by default).
  */
object WebrefCss:

  // ---------- public types ----------

  /** A CSS property OR an atrule descriptor — both share the same on-the-wire shape.
    *
    * The optional `values:` array on a property carries any in-context keywords the spec calls out — the analyzer
    * treats these as additional top-level keyword alternatives even when the main grammar string buries them inside
    * `<type-ref>`s.
    */
  final case class Property(
      name: String,
      value: Option[String] = None,
      initial: Option[String] = None,
      inherited: Option[String] = None,
      prose: Option[String] = None,
      values: List[ValueDef] = Nil,
  )

  /** An at-rule (`@font-face`, `@media`, …) and its descriptors. */
  final case class AtRule(name: String, descriptors: List[Property])

  /** A selector entry from the webref `selectors:` array — a pseudo-class (`:hover`, `:nth-child()`), pseudo-element
    * (`::before`), or combinator. `name` is the catalog key (functional ones end in `()`, e.g. `:nth-child()`); `value`
    * is the full grammar (`:nth-child(An+B [of S]? )`). `prose` becomes scaladoc. `children` carries nested
    * sub-selectors (e.g. `::first-letter` exposes `::prefix`/`::suffix`).
    */
  final case class SelectorDef(
      name: String,
      value: Option[String] = None,
      prose: Option[String] = None,
      children: List[SelectorDef] = Nil,
  )

  /** A value/type definition. `kind` is the JSON `type` field — `"type"`, `"value"`, `"function"`. Children come from a
    * nested `values:` array.
    */
  final case class ValueDef(
      name: String,
      kind: String,
      value: Option[String] = None,
      prose: Option[String] = None,
      children: List[ValueDef] = Nil,
  )

  /** One parsed spec file — what came out of one `<spec>.json`. */
  final case class Spec(
      properties: List[Property],
      atrules: List[AtRule],
      values: List[ValueDef],
      selectors: List[SelectorDef],
  )

  /** Aggregated view across many specs. Last writer wins for same-named properties. */
  final case class Catalog(
      properties: List[Property],
      atrules: List[AtRule],
      values: List[ValueDef],
      selectors: List[SelectorDef],
  )

  object Catalog:
    /** Aggregate properties / atrules / values across multiple parsed specs, last-writer-wins for collisions BUT prefer
      * entries that carry a `value:` field over ones that don't.
      *
      * The preference matters because webref re-declares some properties across specs in a "partial" form (no grammar,
      * just metadata) — e.g. `display` appears in `css-display.json` with the canonical grammar AND in
      * `mathml-core.json` and `css-grid-3.json` with no `value:` field. Naive last-writer-wins would lose the grammar.
      */
    def fromSpecs(specs: List[Spec]): Catalog =
      val propsByName = scala.collection.mutable.LinkedHashMap.empty[String, Property]
      specs.foreach(_.properties.foreach { p =>
        propsByName.get(p.name) match
          case scala.None     => propsByName(p.name) = p
          case Some(existing) =>
            // Replace ONLY if the new entry adds a grammar string, or the existing one didn't
            // carry one. Otherwise preserve the existing (with-grammar) entry.
            (existing.value, p.value) match
              case (scala.None, _)       => propsByName(p.name) = p
              case (Some(_), scala.None) => ()                      // keep existing
              case (Some(_), Some(_))    => propsByName(p.name) = p // both have value: last wins
      })
      // Selectors: last-writer-wins by name across specs, preferring an entry that carries a `value:` grammar (the
      // selectors-5 drafts sometimes re-declare a selectors-4 entry in metadata-only partial form).
      val selsByName = scala.collection.mutable.LinkedHashMap.empty[String, SelectorDef]
      specs.foreach(_.selectors.foreach { s =>
        selsByName.get(s.name) match
          case scala.None     => selsByName(s.name) = s
          case Some(existing) =>
            (existing.value, s.value) match
              case (scala.None, _)       => selsByName(s.name) = s
              case (Some(_), scala.None) => ()
              case (Some(_), Some(_))    => selsByName(s.name) = s
      })
      Catalog(
        properties = propsByName.values.toList,
        atrules = specs.flatMap(_.atrules),
        values = specs.flatMap(_.values),
        selectors = selsByName.values.toList,
      )
    end fromSpecs
  end Catalog

  // ---------- raw JSON shapes (decoded then reduced) ----------
  //
  // The recursive `RawValue` decoder is defined manually (not derived) so it can recur on
  // itself — derivation can't see the self-reference at expansion time.

  private final case class RawValue(
      name: String,
      `type`: String,
      value: Option[String] = None,
      prose: Option[String] = None,
      values: List[RawValue] = Nil,
  )
  private object RawValue:
    given JsonDecoder[RawValue] = DeriveJsonDecoder.gen[RawValue]

  /** Raw property with optional `values:` field decoded as `RawValue`s; reduced before exposure. */
  private final case class RawProperty(
      name: String,
      value: Option[String] = None,
      initial: Option[String] = None,
      inherited: Option[String] = None,
      prose: Option[String] = None,
      values: List[RawValue] = Nil,
  )
  private object RawProperty:
    given JsonDecoder[RawProperty] = DeriveJsonDecoder.gen[RawProperty]

  private final case class RawAtRule(
      name: String,
      descriptors: List[RawProperty] = Nil,
  )
  private object RawAtRule:
    given JsonDecoder[RawAtRule] = DeriveJsonDecoder.gen[RawAtRule]

  // Recurses on itself (nested `values:` sub-selectors), so the decoder is defined manually like RawValue.
  private final case class RawSelector(
      name: String,
      value: Option[String] = None,
      prose: Option[String] = None,
      values: List[RawSelector] = Nil,
  )
  private object RawSelector:
    given JsonDecoder[RawSelector] = DeriveJsonDecoder.gen[RawSelector]

  private final case class RawSpecFile(
      properties: List[RawProperty] = Nil,
      atrules: List[RawAtRule] = Nil,
      values: List[RawValue] = Nil,
      selectors: List[RawSelector] = Nil,
  )
  private object RawSpecFile:
    given JsonDecoder[RawSpecFile] = DeriveJsonDecoder.gen[RawSpecFile]

  // ---------- API ----------

  /** Parse one webref CSS spec JSON document. */
  def parseSpec(json: String): IO[WebrefParseError, Spec] =
    ZIO
      .fromEither(json.fromJson[RawSpecFile])
      .mapError(WebrefParseError("css-spec", _))
      .map(reduce)

  private def reduce(raw: RawSpecFile): Spec =
    Spec(
      properties = raw.properties.map(reduceProperty),
      atrules = raw.atrules.map(r => AtRule(r.name, r.descriptors.map(reduceProperty))),
      values = raw.values.map(reduceValue),
      selectors = raw.selectors.map(reduceSelector),
    )

  private def reduceSelector(r: RawSelector): SelectorDef =
    SelectorDef(
      name = r.name,
      value = r.value.map(fixGrammar),
      prose = r.prose,
      children = r.values.map(reduceSelector),
    )

  private def reduceProperty(r: RawProperty): Property =
    Property(
      name = r.name,
      value = r.value.map(fixGrammar),
      initial = r.initial,
      inherited = r.inherited,
      prose = r.prose,
      values = r.values.map(reduceValue),
    )

  private def reduceValue(r: RawValue): ValueDef =
    ValueDef(
      name = r.name,
      kind = r.`type`,
      value = r.value.map(fixGrammar),
      prose = r.prose,
      children = r.values.map(reduceValue),
    )

  // webref's tooling occasionally emits `@@ unknown symbol "foo"` in place of a `<foo>` type reference
  // (e.g. `path-length` in SVG.json). Restore the intended angle-bracket form so the grammar parses.
  private val unknownSymbol                     = """@@ unknown symbol "([^"]*)"""".r
  private def fixGrammar(value: String): String =
    unknownSymbol.replaceAllIn(value, m => s"<${m.group(1)}>")
end WebrefCss
