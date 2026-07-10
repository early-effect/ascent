package ascent.domgen.aria

import fastparse.*

/** A single ARIA property as it appears in aria-query's `ariaPropsMap.js`. */
final case class AriaProperty(
    name: String,
    `type`: String, // "string" | "boolean" | "tristate" | "id" | "idlist" | "integer" | "number" | "token" | "tokenlist"
    values: List[String],   // Only populated for "token" / "tokenlist" types.
    allowUndefined: Boolean, // For boolean attrs that can be unset (e.g. `aria-expanded` on a non-disclosure widget).
)

/** Parses aria-query's source-style JS literal data files (and the published lib-style JS too) into a typed Scala
  * model.
  *
  * The grammar covers the regular subset of JS we actually see:
  *   - array literals: `[ a, b, ... ]`
  *   - object literals: `{ key: value, 'key': value, key: value, }` (trailing commas + mixed quote styles tolerated,
  *     since aria-query mixes them)
  *   - string atoms: `'foo'` or `"foo"` (no escape sequences; aria-query never needs them)
  *   - boolean atoms: `true` / `false`
  *   - integer atoms: `42` (rare — present for things like default `setsize` markers)
  *
  * The parser is deliberately tolerant: it skips anything outside the top-level array literal (imports, `@flow`
  * headers, `const properties = `, trailing decorators) so the full source file can be passed in as-is.
  */
object AriaQueryParser:

  /** Parse the contents of `ariaPropsMap.js` (or its published-lib equivalent) into a list of [[AriaProperty]] entries.
    * Returns `Left(err)` on a malformed input — the generator should then halt loudly rather than emit a partial
    * catalog.
    */
  def parseProperties(source: String): Either[String, List[AriaProperty]] =
    parseTopLevelArray(source) match
      case Left(err)    => Left(err)
      case Right(items) => Right(items.collect { case Right(p) => p })
      // ^ We collect ONLY successfully-typed property entries. If any entry's shape
      // diverges from the known schema we surface that as a Left below — see flatMap path.
      // For now any non-property entry just doesn't appear; aria-query's array is
      // homogeneous so the collect is exact.

  /** Run the parser, return the parsed top-level array as a list of items.
    *
    * The aria-query source files start with imports and Flow `type` aliases, then declare the array as
    * `const <name>: <FlowType> = [...]`. We can't just find the first `[` (Flow type aliases use `[a, b]` syntax for
    * tuples) or the first `=` (Flow `type X = [...]` aliases use `=` too). The robust answer: find `const ` followed by
    * `=`, then scan forward to the next `[`.
    */
  private def parseTopLevelArray(source: String): Either[String, List[Either[String, AriaProperty]]] =
    val constIdx   = source.indexOf("const ")
    val eqIdx      = if constIdx >= 0 then source.indexOf('=', constIdx) else source.indexOf('=')
    val searchFrom = if eqIdx >= 0 then eqIdx + 1 else 0
    val arrayStart = source.indexOf('[', searchFrom)
    if arrayStart < 0 then Left("no array literal found in source")
    else
      val tail = source.substring(arrayStart)
      fastparse.parse(tail, propertiesArray(using _)) match
        case Parsed.Success(items, _) => Right(items)
        case f: Parsed.Failure        => Left(f.trace().longAggregateMsg)
  end parseTopLevelArray

  // --- grammar ---

  import fastparse.NoWhitespace.*

  /** `<ws>` = whitespace + line/block comments. The vendored files have `// @flow`-style annotations and occasional
    * inline comments.
    */
  private def ws[$: P]: P[Unit] =
    P((CharsWhileIn(" \t\r\n", 1) | lineComment | blockComment).rep)

  private def lineComment[$: P]: P[Unit] =
    P("//" ~ CharsWhile(_ != '\n', 0) ~ ("\n" | End))

  private def blockComment[$: P]: P[Unit] =
    P("/*" ~ (!"*/" ~ AnyChar).rep ~ "*/")

  private def stringLit[$: P]: P[String] =
    P(
      ("'" ~ CharsWhile(c => c != '\'' && c != '\n', 0).! ~ "'") |
        ("\"" ~ CharsWhile(c => c != '"' && c != '\n', 0).! ~ "\"")
    )

  private def integerLit[$: P]: P[String] =
    P(("-".? ~ CharsWhileIn("0-9", 1)).!)

  private def boolLit[$: P]: P[Boolean] =
    P("true").map(_ => true) | P("false").map(_ => false)

  /** A JS object-literal key: either quoted or a bare identifier. */
  private def objectKey[$: P]: P[String] =
    P(stringLit | CharsWhileIn("a-zA-Z_$0-9", 1).!)

  /** A value that can appear inside a property's metadata object. We surface them as strings (the rendered form) plus a
    * flag for booleans, since callers convert.
    */
  private sealed trait AnyValue
  private final case class StrV(s: String)                     extends AnyValue
  private final case class BoolV(b: Boolean)                   extends AnyValue
  private final case class IntV(s: String)                     extends AnyValue
  private final case class ArrV(items: List[AnyValue])         extends AnyValue
  private final case class ObjV(fields: Map[String, AnyValue]) extends AnyValue

  private def anyValue[$: P]: P[AnyValue] =
    P(
      stringLit.map(StrV(_)) |
        boolLit.map(BoolV(_)) |
        integerLit.map(IntV(_)) |
        arrayValue |
        objectValue
    )

  // Each kind below uses the canonical fastparse shape that works in CssGrammar:
  //   `head ~ (sep ~ head).rep`
  // wrapped with `.map { (h, t) => h :: t.toList }`. The empty-list case is a separate
  // alternative under `|`. aria-query's source files don't use trailing commas, so we
  // don't need to handle them — keeps the grammar regular and avoids fastparse-3 +
  // Scala-3 inference glitches with `.?` on chained results.

  /** A bare `,` separator. Whitespace handling is folded into the `~` chain at the call site so this stays a clean
    * `P[Unit]`.
    */
  private def comma[$: P]: P[Unit] = P(ws ~ "," ~ ws)

  /** Generic non-empty list helpers. Two flavours per element type because fastparse's `rep(sep = ...)` form was
    * returning a Repeater that didn't include the leading- whitespace cycle correctly between elements; the manual
    * `head ~ (sep ~ head).rep` shape (the same one CssGrammar uses) makes the intent explicit and works.
    */
  private def anyValueList[$: P]: P[Seq[AnyValue]] =
    P(anyValue ~ (comma ~ anyValue).rep).map { case (h, t) => h +: t }

  // A bare `key: value` rule returns a TUPLE `(String, AnyValue)`; under fastparse's `~` that
  // tuple auto-flattens into multiple positional captures, which breaks `head ~ rep`. Wrapping
  // each field in a small `Field` case class forces the Sequencer to treat it as one unit.
  private final case class Field(name: String, value: AnyValue)
  private def objectFieldF[$: P]: P[Field] =
    P(objectKey ~ ws ~ ":" ~ ws ~ anyValue).map { case (k, v) => Field(k, v) }

  private def objectFieldList[$: P]: P[Seq[Field]] =
    P(objectFieldF ~ (comma ~ objectFieldF).rep).map { case (h, t) => h +: t }

  private def entryList[$: P]: P[Seq[Either[String, AriaProperty]]] =
    P(entry ~ (comma ~ entry).rep).map { case (h, t) => h +: t }

  /** Optional trailing-comma + whitespace before a closing `]` or `}`. aria-query's source uses trailing commas in
    * object literals (e.g. `{ type: 'integer', }`) so we tolerate them here. Kept as a standalone `def` so fastparse's
    * inference picks up a clean `P[Unit]` rather than wrestling with `.?` on the call site.
    */
  private def trailingCloser[$: P]: P[Unit] = P(("," ~ ws).? ~ ws)

  private def arrayValue[$: P]: P[ArrV] =
    P("[" ~ ws ~ anyValueList.? ~ trailingCloser ~ "]").map { items =>
      ArrV(items.getOrElse(Nil).toList)
    }

  private def objectValue[$: P]: P[ObjV] =
    P("{" ~ ws ~ objectFieldList.? ~ trailingCloser ~ "}").map { items =>
      ObjV(items.getOrElse(Nil).iterator.map(f => f.name -> f.value).toMap)
    }

  /** Top-level: the array of `[name, {fields}]` pairs. Tolerates anything trailing the top-level array (decorator wrap,
    * exports) by consuming it via `AnyChar.rep`. Also accepts an optional trailing comma + whitespace before the
    * closing `]`.
    */
  private def propertiesArray[$: P]: P[List[Either[String, AriaProperty]]] =
    P(ws ~ "[" ~ ws ~ entryList.? ~ trailingCloser ~ "]" ~ AnyChar.rep).map(items => items.getOrElse(Nil).toList)

  /** A single `[name, {fields}]` 2-tuple, decoded into [[AriaProperty]]. */
  private def entry[$: P]: P[Either[String, AriaProperty]] =
    P("[" ~ ws ~ stringLit ~ ws ~ "," ~ ws ~ objectValue ~ ws ~ ",".? ~ ws ~ "]").map { case (name, ObjV(fields)) =>
      decodeProperty(name, fields)
    }

  private def decodeProperty(name: String, fields: Map[String, AnyValue]): Either[String, AriaProperty] =
    fields.get("type") match
      case Some(StrV(t)) =>
        val values = fields.get("values") match
          case Some(ArrV(items)) =>
            items.collect {
              case StrV(s)  => s
              case BoolV(b) => b.toString
              case IntV(s)  => s
            }
          case _ => Nil
        val allowUndefined = fields.get("allowundefined") match
          case Some(BoolV(b)) => b
          case _              => false
        Right(AriaProperty(name, t, values, allowUndefined))
      case _ => Left(s"missing or non-string `type` field on $name")

end AriaQueryParser
