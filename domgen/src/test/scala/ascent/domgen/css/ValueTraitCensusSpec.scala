package ascent.domgen.css

import zio.*
import zio.test.*

/** Guardrail over the real vendored CSS catalog (vs [[CssGeneratorSpec]]'s inline fixtures): runs the generator over
  * the whole `data/webref/css` snapshot and asserts structural invariants — keywords live on shared value-type traits,
  * no headline duplication, and the bare-property count stays within a documented budget.
  */
object ValueTraitCensusSpec extends ZIOSpecDefault:

  /** Load the whole vendored CSS catalog + build the value lookup, exactly as `Main` does. */
  private def loadCatalog: Task[(WebrefCss.Catalog, Map[String, WebrefCss.ValueDef])] =
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
      .flatMap { texts =>
        ZIO.foreach(texts.toVector)(t => WebrefCss.parseSpec(t).orDie).map { specs =>
          val cat          = WebrefCss.Catalog.fromSpecs(specs.toList)
          val valuesByName = cat.values.foldLeft(Map.empty[String, WebrefCss.ValueDef])((m, v) => m.updated(v.name, v))
          (cat, valuesByName)
        }
      }
  end loadCatalog

  def spec = suite("CSS value-trait census (real webref)")(
    test("the headline duplication is gone: no property inlines a <line-width> keyword") {
      // `thin/medium/thick/hairline` belong to `trait LineWidth`; 34 properties used to each inline them.
      // `def hairline` must now appear only inside the trait file, never in a property object.
      for (cat, values) <- loadCatalog
      yield
        val src = CssGenerator.generate(cat.properties, values)
        assertTrue(!src.contains("def hairline"))
    },
    test("no generated value-trait shadows a hand-written authoring/value type (e.g. CounterStyle)") {
      // A bare generated `trait CounterStyle` would clash with the authoring type `ascent.css.CounterStyle`,
      // making `import ascent.css.Styles.*` ambiguous; the reserved-names guard suffixes clashers with `Kw`.
      for (_, values) <- loadCatalog
      yield
        val traits = CssGenerator.generateValueTraits(values)
        assertTrue(
          !traits.contains("trait CounterStyle extends"),
          traits.contains("trait CounterStyleKw"),
          !traits.contains("trait Color extends"), // value-type ADT names likewise protected
          !traits.contains("trait Transform extends"),
        )
    },
    test("a representative shared keyword trait is generated with its keywords + scaladoc") {
      for (_, values) <- loadCatalog
      yield
        val traits = CssGenerator.generateValueTraits(values)
        assertTrue(
          traits.contains("trait LineWidth"),
          traits.contains("""def thin: Declaration = apply("thin")"""),
          traits.contains("""def hairline: Declaration = apply("hairline")"""),
          traits.contains("/** Negative values are invalid."), // webref prose became scaladoc on the keyword
          traits.contains("trait LineStyle extends DS with None"),
          traits.contains("""def dashed: Declaration = apply("dashed")"""),
        )
    },
    test("border-width family mixes the LineWidth trait instead of inlining keywords") {
      for (cat, values) <- loadCatalog
      yield
        val src = CssGenerator.generate(cat.properties, values)
        assertTrue(
          // border-width is a 1–4 value box of <line-width>: LengthBox (multi-value overloads) + per-value
          // Length + the LineWidth keyword trait, never inlining thin/medium/thick.
          src.contains("""object borderWidth extends DS("border-width") with Length with LengthBox with LineWidth"""),
          src.contains("with LineWidth"),
        )
    },
    test("the generated source carries synthesized property scaladoc (name + grammar + initial/inherited)") {
      for (cat, values) <- loadCatalog
      yield
        val src = CssGenerator.generate(cat.properties, values)
        assertTrue(
          src.contains("`font-size` —"), // e.g. font-size's synthesized doc
          src.contains("Inherited:"),
        )
    },
    test("shorthand-component keyword types get a composable CssValue enum (sibling to the property trait)") {
      // <single-animation-fill-mode> is a component of the <single-animation> `||` shorthand, so it gets BOTH a
      // property-bound trait (animationFillMode.both) and a top-level value enum (SingleAnimationFillMode.Both).
      for (cat, values) <- loadCatalog
      yield
        val enums = CssGenerator.generateKeywordValues(values, cat.properties)
        assertTrue(
          enums.contains("enum SingleAnimationFillMode(val render: String) extends CssValue"),
          enums.contains("""case Both extends SingleAnimationFillMode("both")"""),
          enums.contains("""case AlternateReverse extends SingleAnimationDirection("alternate-reverse")"""),
          // <number> branch → a parameterized Count case carrying the raw value
          enums.contains("case Count(n: Double) extends SingleAnimationIterationCount"),
          enums.contains("override def toString: String = render"),
        )
    },
    test("a keyword type with a hand-authored CssValue (line-style → enum LineStyle) is NOT re-generated as an enum") {
      for (cat, values) <- loadCatalog
      yield
        val enums = CssGenerator.generateKeywordValues(values, cat.properties)
        assertTrue(
          !enums.contains("enum LineStyle"), // Border.scala owns it; generating a second would redefine
          // but standalone-keyword properties (display, cursor) get NO enum — only shorthand components do
          !enums.contains("enum Display"),
          !enums.contains("enum Cursor"),
        )
    },
    test("bare-property budget: only genuinely open-ended grammars stay bare (documented allow-list)") {
      // A bare property (`object foo extends DS("foo")`, no traits/body) is acceptable only for open-ended
      // grammars: vendor-prefixed, <custom-ident>, <string>, mega-shorthands. A rise past the budget means
      // a property that should be typed got stranded.
      for (cat, values) <- loadCatalog
      yield
        val src  = CssGenerator.generate(cat.properties, values)
        val bare = "(?m)^  object ([A-Za-z0-9_`]+) extends DS\\(\"[^\"]+\"\\)$".r
          .findAllMatchIn(src)
          .map(_.group(1))
          .toList
        assertTrue(bare.size <= 43)
    },
  )
end ValueTraitCensusSpec
