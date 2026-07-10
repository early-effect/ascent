package ascent.css

import ascent.ast.UI
import zio.*
import zio.test.*

/** The per-render style catalog: `record` de-dups by key, flushes newly-seen blocks to its sink, and `snapshot` reports
  * everything in registration order. No process-global state — each [[StyleRegistry.make]] is independent, so this
  * suite needs neither `@@ sequential` nor a reset hook.
  */
object StyleRegistrySpec extends ZIOSpecDefault:

  /** Make a registry over a capturing sink, returning both so a test can drive `record` and read what flushed. */
  private def registryWithSink: UIO[(StyleRegistry, StyleSink.Capturing)] =
    StyleSink.capturing.flatMap(sink => StyleRegistry.make(sink).map(_ -> sink))

  def spec = suite("StyleRegistry")(
    test("record captures blocks and snapshot reflects them in registration order") {
      for
        (reg, _) <- registryWithSink
        _        <- reg.record(Vector("a" -> ".a{}", "b" -> ".b{}"))
        snap     <- reg.snapshot
      yield assertTrue(snap == Vector("a" -> ".a{}", "b" -> ".b{}"))
    },
    test("record flushes each newly-seen block to the sink, in order") {
      for
        (reg, sink) <- registryWithSink
        _           <- reg.record(Vector("a" -> ".a{}", "b" -> ".b{}"))
        flushed     <- sink.captured
      yield assertTrue(flushed == Vector("a" -> ".a{}", "b" -> ".b{}"))
    },
    test("a key seen before does NOT flush again and its first value/position wins") {
      for
        (reg, sink) <- registryWithSink
        _           <- reg.record(Vector("a" -> ".a{first}"))
        _           <- reg.record(Vector("b" -> ".b{}", "a" -> ".a{second}"))
        flushed     <- sink.captured
        snap        <- reg.snapshot
      yield assertTrue(
        // `a` flushed once (with its first value); only the genuinely-new `b` flushed on the second call.
        flushed == Vector("a" -> ".a{first}", "b" -> ".b{}"),
        snap == Vector("a" -> ".a{first}", "b" -> ".b{}"),
      )
    },
    test("duplicate keys WITHIN one record batch flush once") {
      for
        (reg, sink) <- registryWithSink
        _           <- reg.record(Vector("a" -> ".a{}", "a" -> ".a{}", "b" -> ".b{}"))
        flushed     <- sink.captured
      yield assertTrue(flushed == Vector("a" -> ".a{}", "b" -> ".b{}"))
    },
    test("recording nothing new flushes nothing") {
      for
        (reg, sink) <- registryWithSink
        _           <- reg.record(Vector("a" -> ".a{}"))
        _           <- reg.record(Vector("a" -> ".a{}"))
        flushed     <- sink.captured
      yield assertTrue(flushed == Vector("a" -> ".a{}"))
    },
    test("two registries are fully isolated — neither sees the other's blocks") {
      for
        (regA, _) <- registryWithSink
        (regB, _) <- registryWithSink
        _         <- regA.record(Vector("only-a" -> ".a{}"))
        _         <- regB.record(Vector("only-b" -> ".b{}"))
        snapA     <- regA.snapshot
        snapB     <- regB.snapshot
      yield assertTrue(
        snapA.map(_._1) == Vector("only-a"),
        snapB.map(_._1) == Vector("only-b"),
      )
    },
    suite("primitive contributions")(
      test("CssClass.contributionBlocks is (className -> renderCss)") {
        object Banner extends CssClass(Declaration("background", "yellow"))
        val blocks = Banner.contributionBlocks
        assertTrue(
          blocks == Vector(Banner.className -> Banner.renderCss),
          blocks.head._2.contains("background: yellow;"),
        )
      },
      test("a CssClass that .use-s a Keyframes pulls the @keyframes block in (transitive dependency as DATA)") {
        object Drift extends Keyframes("reg-drift", Frame.from(Declaration("opacity", "0")))
        object Card  extends CssClass(Drift.use("1s ease"))
        val keys = Card.contributionBlocks.map(_._1)
        assertTrue(
          keys.contains(Card.className),
          keys.contains("keyframes-reg-drift"),
          // keyframe precedes the class rule that references it
          keys.indexOf("keyframes-reg-drift") < keys.indexOf(Card.className),
        )
      },
      test("a Keyframes referenced by a NESTED selector is still collected") {
        object Spin extends Keyframes("reg-spin", Frame.from(Declaration("transform", "rotate(0)")))
        object Card extends CssClass(Selector(":hover", Spin.use("2s linear infinite")))
        assertTrue(Card.contributionBlocks.map(_._1).contains("keyframes-reg-spin"))
      },
      test("the string form animation(s\"$Kf ...\") does NOT track the dependency") {
        object Ghost extends Keyframes("reg-ghost", Frame.from(Declaration("opacity", "0")))
        object Card  extends CssClass(Declaration("animation", s"$Ghost 1s"))
        assertTrue(!Card.contributionBlocks.map(_._1).contains("keyframes-reg-ghost"))
      },
      test("CssScope.contributionBlocks is keyed scope-<id> with the rule CSS") {
        val node   = UI.Element("div", Vector.empty, Vector.empty)
        val scope  = CssClass.targeting(node, Declaration("color", "red"))
        val blocks = scope.contributionBlocks
        assertTrue(
          blocks.map(_._1) == Vector(scope.sinkKey),
          scope.sinkKey.startsWith("scope-"),
          blocks.head._2.contains("color: red;"),
        )
      },
      test("GlobalStyle.contributionBlocks is one entry per rule") {
        object Chrome
            extends GlobalStyle(
              GlobalRule.raw("gs-page-body", "body { margin: 0; }"),
              FontFace(Declaration("font-family", "\"GsReg\"")),
            )
        assertTrue(Chrome.contributionBlocks.map(_._1) == Vector("gs-page-body", "font-face-GsReg"))
      },
    ),
  )
end StyleRegistrySpec
