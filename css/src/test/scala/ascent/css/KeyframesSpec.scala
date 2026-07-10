package ascent.css

import zio.test.*

/** [[Keyframes]] is an installable CSS primitive that emits an `@keyframes <name> { ... }` block and whose `name` can
  * be embedded in `animation:` declarations.
  */
object KeyframesSpec extends ZIOSpecDefault:

  def spec = suite("Keyframes")(
    suite("Frame")(
      test("Frame.from is the `0%` stop") {
        val f = Frame.from(Declaration("opacity", "0"))
        assertTrue(
          f.stops == List("from"),
          f.declarations.map(_.render) == List("opacity: 0;"),
        )
      },
      test("Frame.to is the `100%` stop") {
        val f = Frame.to(Declaration("opacity", "1"))
        assertTrue(f.stops == List("to"))
      },
      test("Frame.pct(n) is the `<n>%` stop with platform-stable Double formatting") {
        val f = Frame.pct(50)(Declaration("opacity", "0.5"))
        assertTrue(f.stops == List("50%"))
      },
      test("Frame.at supports multi-stop shared-body frames (`0%, 100%`)") {
        val f = Frame.at(List("0%", "100%"))(Declaration("opacity", "0"))
        assertTrue(f.stops == List("0%", "100%"))
      },
    ),
    suite("Keyframes rule body")(
      test("renderRule produces `@keyframes <name> { <frames> }`") {
        object Pulse
            extends Keyframes("pulse", Frame.from(Declaration("opacity", "1")), Frame.to(Declaration("opacity", "0")))
        val src = Pulse.renderRule
        assertTrue(
          src.contains("@keyframes pulse {"),
          src.contains("from {"),
          src.contains("opacity: 1;"),
          src.contains("to {"),
          src.contains("opacity: 0;"),
          src.endsWith("}\n"),
        )
      },
      test("a multi-stop frame renders its stops separated by `, `") {
        object Flash
            extends Keyframes(
              "flash",
              Frame.at(List("0%", "100%"))(Declaration("opacity", "0")),
              Frame.at(List("50%"))(Declaration("opacity", "1")),
            )
        val src = Flash.renderRule
        assertTrue(
          src.contains("@keyframes flash {"),
          src.contains("0%, 100% {"),
          src.contains("50% {"),
        )
      },
      test("toString returns the animation NAME so it embeds into an animation: shorthand string, not the rule body") {
        object Slide extends Keyframes("slide-in", Frame.from(Declaration("opacity", "0")))
        assertTrue(Slide.toString == "slide-in")
      },
      test("the animation name is available as `name` for explicit references") {
        object Wobble extends Keyframes("wobble", Frame.from(Declaration("opacity", "1")))
        assertTrue(Wobble.name == "wobble")
      },
      test("with no explicit name, the name is auto-derived from the Scala object name (like CssClass)") {
        object AutoNamed  extends Keyframes(Frame.from(Declaration("opacity", "0")))
        object OtherNamed extends Keyframes(Frame.from(Declaration("opacity", "1")))
        assertTrue(
          AutoNamed.name.contains("AutoNamed"),            // carries the object's simple name
          AutoNamed.name.matches("[A-Za-z][A-Za-z0-9-]*"), // a valid, `$`-free CSS identifier
          AutoNamed.name != OtherNamed.name,               // distinct objects → distinct names
          AutoNamed.renderRule.contains(s"@keyframes ${AutoNamed.name} {"),
        )
      },
    ),
    suite("use — animation shorthand")(
      test("typed use renders `animation: <name> <duration> <timing>` with defaults omitted") {
        object Pulse extends Keyframes("pulse", Frame.from(Declaration("opacity", "0")))
        assertTrue(Pulse.use(Time.s(0.4)).render == "animation: pulse 0.4s ease;")
      },
      test("typed use includes iteration-count / direction / fill when supplied, in grammar order") {
        object Glow extends Keyframes("glow", Frame.from(Declaration("opacity", "0")))
        val decl = Glow.use(
          Time.s(4),
          TimingFunction.easeInOut,
          iterations = Some(SingleAnimationIterationCount.Infinite),
          fill = Some(SingleAnimationFillMode.Both),
        )
        // Time renders platform-stably with a decimal (`4.0s`); keyword enums render bare tokens.
        assertTrue(decl.render == "animation: glow 4.0s ease-in-out infinite both;")
      },
      test("typed use records the keyframe as a data dependency (referencedKeyframes)") {
        object Spin extends Keyframes("spin", Frame.from(Declaration("opacity", "0")))
        assertTrue(Spin.use(Time.s(1)).referencedKeyframes.map(_.name) == Seq("spin"))
      },
      test("the string escape hatch still works and still records the dependency") {
        object Slide extends Keyframes("slide", Frame.from(Declaration("opacity", "0")))
        val decl = Slide.use("2s ease-out both")
        assertTrue(
          decl.render == "animation: slide 2s ease-out both;",
          decl.referencedKeyframes.map(_.name) == Seq("slide"),
        )
      },
    ),
    suite("StyleSink integration")(
      test("installInto registers the rule under `keyframes-<name>`") {
        object Bounce
            extends Keyframes(
              "bounce",
              Frame.from(Declaration("transform", "translateY(0)")),
              Frame.to(Declaration("transform", "translateY(-8px)")),
            )
        for
          sink  <- StyleSink.capturing
          _     <- Bounce.installInto(sink)
          rules <- sink.captured
        yield assertTrue(
          rules.size == 1,
          rules.head._1 == "keyframes-bounce",
          rules.head._2.contains("@keyframes bounce {"),
          rules.head._2.contains("transform: translateY(0);"),
          rules.head._2.contains("transform: translateY(-8px);"),
        )
        end for
      },
      test("installing twice replaces the previous entry (idempotent)") {
        object Spin extends Keyframes("spin", Frame.from(Declaration("opacity", "0.1")))
        for
          sink  <- StyleSink.capturing
          _     <- Spin.installInto(sink)
          _     <- Spin.installInto(sink)
          rules <- sink.captured
        yield assertTrue(rules.size == 1)
      },
    ),
  )
end KeyframesSpec
