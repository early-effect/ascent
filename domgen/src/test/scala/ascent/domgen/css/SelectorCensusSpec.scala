package ascent.domgen.css

import zio.*
import zio.test.*

/** Guardrail over the real vendored selectors catalog: every simple (argument-less) pseudo-class / pseudo-element
  * webref lists must emit a `val`, and the documented exclusions must be the only omissions. A new pseudo in a future
  * re-vendoring fails this until it's surfaced — no silent gaps.
  */
object SelectorCensusSpec extends ZIOSpecDefault:

  private def loadCatalog: Task[WebrefCss.Catalog] =
    import java.nio.file.*
    import scala.jdk.CollectionConverters.*
    ZIO
      .attemptBlocking {
        val dir = Paths.get("data/webref/css")
        Files
          .list(dir)
          .iterator()
          .asScala
          .toList
          .filter(_.toString.endsWith(".json"))
          .sortBy(_.getFileName.toString)
          .map(p => Files.readString(p))
      }
      .flatMap(texts => ZIO.foreach(texts.toVector)(t => WebrefCss.parseSpec(t).orDie))
      .map(specs => WebrefCss.Catalog.fromSpecs(specs.toList))
  end loadCatalog

  /** The same camelCase the generator uses, so we can predict the emitted val names. */
  private def camelCase(s: String): String =
    val parts = s.split('-')
    if parts.length <= 1 then s else parts.head + parts.tail.map(_.capitalize).mkString

  /** A simple selector per the generator's definition: `:name` / `::name`, no parens. */
  private def isSimple(name: String): Boolean =
    name.startsWith(":") && !name.endsWith("()") &&
      name.drop(1).dropWhile(_ == ':').forall(c => c.isLetterOrDigit || c == '-')

  // Documented legacy single-colon pseudo-elements: only the `::` spelling is emitted.
  private val legacySingleColon = Set(":before", ":after", ":first-line", ":first-letter")

  // A representative slice of the element catalog (incl. a Scala keyword) for the tag-selector tests.
  private val sampleElements: List[(String, String)] =
    List("div" -> "div", "button" -> "button", "li" -> "li", "`object`" -> "object", "`var`" -> "var")

  def spec = suite("CSS selector census (real webref)")(
    test("every simple pseudo-class is surfaced as a val") {
      for cat <- loadCatalog
      yield
        val src             = SelectorGenerator.generate(cat, sampleElements)
        val expectedClasses = cat.selectors
          .map(_.name)
          .filter(isSimple)
          .filterNot(_.startsWith("::"))
          .filterNot(legacySingleColon.contains)
          .distinct
        val missing = expectedClasses.filterNot { n =>
          src.contains(s"""val ${camelCase(n.dropWhile(_ == ':'))}: PseudoClass = simple("${n.dropWhile(_ == ':')}")""")
        }
        assertTrue(missing.isEmpty, expectedClasses.nonEmpty)
    },
    test("every simple pseudo-element is surfaced as a val") {
      for cat <- loadCatalog
      yield
        val src           = SelectorGenerator.generate(cat, sampleElements)
        val expectedElems = cat.selectors
          .map(_.name)
          .filter(n => isSimple(n) && n.startsWith("::"))
          .distinct
        val missing = expectedElems.filterNot { n =>
          src.contains(
            s"""val ${camelCase(n.dropWhile(_ == ':'))}: PseudoElement = simple("${n.dropWhile(_ == ':')}")"""
          )
        }
        assertTrue(missing.isEmpty, expectedElems.nonEmpty)
    },
    test("functional selectors and combinators are NOT emitted as vals (they're hand-written / Sel methods)") {
      for cat <- loadCatalog
      yield
        val src = SelectorGenerator.generate(cat, sampleElements)
        // Every emitted bare name is `simple("<letters/digits/hyphens>")` — no leading colon, no parens. So a
        // functional name (`nth-child(...)`) or combinator could never have leaked into a generated val.
        val simpleArgs = """simple\("([^"]*)"\)""".r.findAllMatchIn(src).map(_.group(1)).toList
        val bad        = simpleArgs.filter(a => a.startsWith(":") || a.contains("(") || a.contains(")"))
        assertTrue(
          bad.isEmpty,
          simpleArgs.nonEmpty,
          !simpleArgs.contains("nth-child"), // a representative functional name never becomes a simple val
        )
    },
    test("the modern :: spelling wins; the legacy single-colon alias is dropped") {
      for cat <- loadCatalog
      yield
        val src         = SelectorGenerator.generate(cat, sampleElements)
        val beforeCount = src.split("""simple\("before"\)""", -1).length - 1
        assertTrue(
          src.contains("""val before: PseudoElement = simple("before")"""),
          beforeCount == 1, // exactly one — the `::` entry, not also the legacy `:before`
        )
    },
    test("element tags are surfaced, keyword names backticked, dom name preserved") {
      for cat <- loadCatalog
      yield
        val src = SelectorGenerator.generate(cat, sampleElements)
        assertTrue(
          src.contains("""val button: Sel = tag("button")"""),
          src.contains("""val div: Sel = tag("div")"""),
          // Scala keyword element names are backticked but keep the bare dom name in the selector string.
          src.contains("""val `object`: Sel = tag("object")"""),
          src.contains("""val `var`: Sel = tag("var")"""),
          src.contains("trait ElemsGenerated:"),
        )
    },
  )
end SelectorCensusSpec
