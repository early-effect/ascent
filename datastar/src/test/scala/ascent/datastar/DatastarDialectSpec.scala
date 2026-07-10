package ascent.datastar

import zio.json.ast.Json
import zio.test.*

/** Parses the SSE data blocks a datastar server emits; data-line shapes mirror the SDK's `DatastarEvent` encoder. */
object DatastarDialectSpec extends ZIOSpecDefault:

  def spec = suite("Datastar dialect")(
    suite("eventNames")(
      test("handles exactly the two patch events") {
        assertTrue(Datastar.eventNames == Set("datastar-patch-signals", "datastar-patch-elements"))
      }
    ),
    suite("patch-signals")(
      test("parses a signals object") {
        val r = Datastar.parse("datastar-patch-signals", "signals {\"count\":1}")
        assertTrue(
          r == Right(RemoteEvent.PatchSignals(Json.Obj(zio.Chunk("count" -> Json.Num(1))), onlyIfMissing = false))
        )
      },
      test("honours onlyIfMissing") {
        val r = Datastar.parse("datastar-patch-signals", "onlyIfMissing true\nsignals {\"a\":1}")
        assertTrue(r.exists { case RemoteEvent.PatchSignals(_, oim) => oim; case _ => false })
      },
      test("missing signals line is an error, not a crash") {
        assertTrue(Datastar.parse("datastar-patch-signals", "onlyIfMissing true").isLeft)
      },
      test("malformed JSON is an error, not a crash") {
        assertTrue(Datastar.parse("datastar-patch-signals", "signals {not json").isLeft)
      },
      test("a non-object signals payload is rejected") {
        assertTrue(Datastar.parse("datastar-patch-signals", "signals 42").isLeft)
      },
    ),
    suite("patch-elements")(
      test("parses selector + mode + elements") {
        val data = "selector #foo\nmode inner\nelements <div>hi</div>"
        assertTrue(
          Datastar.parse("datastar-patch-elements", data) ==
            Right(
              RemoteEvent
                .PatchElements("<div>hi</div>", Some("#foo"), ElementPatchMode.Inner, useViewTransition = false)
            )
        )
      },
      test("defaults mode to Outer when no mode line") {
        val r = Datastar.parse("datastar-patch-elements", "elements <p>x</p>")
        assertTrue(r.exists {
          case RemoteEvent.PatchElements(_, _, ElementPatchMode.Outer, _) => true
          case _                                                          => false
        })
      },
      test("re-joins multiple elements lines with newline") {
        val data = "elements <div>\nelements   hi\nelements </div>"
        val html = Datastar.parse("datastar-patch-elements", data).map {
          case RemoteEvent.PatchElements(h, _, _, _) => h
          case _                                     => ""
        }
        assertTrue(html == Right("<div>\n  hi\n</div>"))
      },
      test("honours useViewTransition") {
        val r = Datastar.parse("datastar-patch-elements", "useViewTransition true\nelements <p>x</p>")
        assertTrue(r.exists { case RemoteEvent.PatchElements(_, _, _, vt) => vt; case _ => false })
      },
      test("an unknown mode token is an error, not a crash") {
        assertTrue(Datastar.parse("datastar-patch-elements", "mode sideways\nelements <p>x</p>").isLeft)
      },
      test("a remove (mode remove, selector, empty elements) parses") {
        val r = Datastar.parse("datastar-patch-elements", "selector #gone\nmode remove")
        assertTrue(
          r == Right(RemoteEvent.PatchElements("", Some("#gone"), ElementPatchMode.Remove, useViewTransition = false))
        )
      },
    ),
    suite("unknown event")(
      test("an unrecognised event name is an error") {
        assertTrue(Datastar.parse("datastar-execute-script", "anything").isLeft)
      }
    ),
  )
end DatastarDialectSpec
