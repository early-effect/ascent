package ascent.domgen.aria

import zio.test.*

/** Parser for aria-query's source-style JS literal data, e.g.:
  * {{{
  *   const properties = [
  *     ['aria-activedescendant', { 'type': 'id' }],
  *     ['aria-autocomplete',      { 'type': 'token', 'values': ['inline', 'list'] }],
  *   ];
  * }}}
  * Real input also carries imports, `@flow` headers, and a trailing iteration-decorator wrap around the array.
  */
object AriaQueryParserSpec extends ZIOSpecDefault:

  def spec = suite("aria-query parser")(
    test("parses a single string-typed property") {
      val src    = """[ ['aria-label', { 'type': 'string' }] ]"""
      val result = AriaQueryParser.parseProperties(src)
      assertTrue(
        result == Right(
          List(
            AriaProperty("aria-label", "string", values = Nil, allowUndefined = false)
          )
        )
      )
    },
    test("parses a token-typed property with values list") {
      val src    = """[
        ['aria-autocomplete', { 'type': 'token', 'values': ['inline', 'list', 'both', 'none'] }]
      ]"""
      val result = AriaQueryParser.parseProperties(src)
      result match
        case Right(List(p)) =>
          assertTrue(
            p.name == "aria-autocomplete",
            p.`type` == "token",
            p.values == List("inline", "list", "both", "none"),
          )
        case other => assertNever(s"expected one prop, got $other")
    },
    test("parses values that mix strings + booleans, surfacing booleans as their string form") {
      val src    = """[
        ['aria-current', { 'type': 'token', 'values': ['page', 'step', true, false] }]
      ]"""
      val result = AriaQueryParser.parseProperties(src)
      result match
        case Right(List(p)) => assertTrue(p.values == List("page", "step", "true", "false"))
        case other          => assertNever(s"$other")
    },
    test("captures `allowundefined: true` so the renderer can default to `Option[T]`") {
      val src    = """[
        ['aria-expanded', { 'type': 'boolean', 'allowundefined': true }]
      ]"""
      val result = AriaQueryParser.parseProperties(src)
      result match
        case Right(List(p)) => assertTrue(p.allowUndefined == true, p.`type` == "boolean")
        case other          => assertNever(s"$other")
    },
    test("accepts both quoted and unquoted JS object keys (real aria-query mixes them)") {
      val src    = """[
        ['aria-rowcount', { type: 'integer' }],
        ['aria-rowindex', { 'type': 'integer' }]
      ]"""
      val result = AriaQueryParser.parseProperties(src)
      result match
        case Right(props) =>
          assertTrue(
            props.size == 2,
            props.forall(_.`type` == "integer"),
          )
        case other => assertNever(s"$other")
    },
    test("ignores a leading `const properties = ` declaration and trailing semicolons / decorators") {
      // The parser runs on the full source file: it strips everything before the first `[` and after
      // the matching close bracket of the top-level array.
      val src = """
        // @flow
        import iterationDecorator from "./util/iterationDecorator";
        const properties = [
          ['aria-label', { 'type': 'string' }],
          ['aria-checked', { 'type': 'tristate' }]
        ];
        export default iterationDecorator(properties);
      """.stripIndent()
      val result = AriaQueryParser.parseProperties(src)
      result match
        case Right(props) =>
          assertTrue(
            props.map(_.name) == List("aria-label", "aria-checked")
          )
        case other => assertNever(s"$other")
    },
    test("the real vendored ariaPropsMap.js parses without crashing and surfaces every property") {
      import scala.io.Source
      val text =
        val src = Source.fromFile("data/aria-query/ariaPropsMap.js", "UTF-8")
        try src.mkString
        finally src.close()
      AriaQueryParser.parseProperties(text) match
        case Right(props) =>
          assertTrue(
            // Pin the well-known set so a future version bump can't silently drop one.
            props.exists(_.name == "aria-label"),
            props.exists(_.name == "aria-pressed"),
            props.exists(_.name == "aria-checked"),
            props.exists(_.name == "aria-live"),
            props.exists(_.name == "aria-current"),
            props.exists(_.name == "aria-hidden"),
            props.exists(_.name == "aria-expanded"),
            props.size >= 45, // aria-query 5.3.2 ships ~50; pinned loosely
          )
        case Left(err) => assertNever(s"parse failed: $err")
      end match
    },
  )
end AriaQueryParserSpec
