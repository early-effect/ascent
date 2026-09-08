package ascent.chekhov

import ascent.*
import ascent.ast.Attr
import ascent.dom
import ascent.domtypes.AttrValue
import ascent.dsl.*
import ascent.chekhov.AscentChekhov.withMounted
import zio.*
import zio.test.*

/** Live `withMounted` + typed handles under ChekhovJSEnv. */
object AscentChekhovSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(15.seconds),
    )

  private def testId(id: String): Attr[Any] =
    Attr.StaticAttr("data-testid", AttrValue.Str(id))

  def spec =
    suite("AscentChekhov")(
      test("withMounted mounts a counter and supports button click") {
        for
          count <- sq(0)
          ui = E.div(
            testId("root"),
            E.span(testId("count"), count.map(_.toString)),
            E.button(
              testId("inc"),
              A.typ("button"),
              Ev.onClick(_ => count.update(_ + 1)),
              "Increment",
            ),
          )
          result <- withMounted(ui) { root =>
            for
              before <- root.getByTestId("count").innerText
              _      <- root.button("inc").click
              after  <- waitForText(root, "count", "1")
            yield assertTrue(before == "0", after == "1")
          }
        yield result
      },
      test("withMounted removes the chekhov root on exit") {
        for
          ref    <- Ref.make[Option[dom.Element]](None)
          during <- withMounted(E.div(testId("x"), "hi")) { root =>
            ref.set(Some(root.element)).as(root.element.getAttribute("data-chekhov-root") == "true")
          }
          detached <- ref.get.map(_.exists(el => !el.isConnected))
        yield assertTrue(during, detached)
      },
      test("concurrent withMounted scopes are isolated from each other") {
        def scope(
            tag: String,
            other: String,
            ref: Ref[Option[dom.Element]],
            ready: Promise[Nothing, Unit],
            otherReady: Promise[Nothing, Unit],
        ) =
          withMounted(E.div(testId(tag), tag)) { root =>
            for
              _  <- ref.set(Some(root.element))
              _  <- ready.succeed(())
              _  <- otherReady.await
              ok <- ZIO.succeed(
                root.element.getAttribute("data-chekhov-root") == "true" &&
                  !root.element.ownerDocument.eq(dom.document) &&
                  Option(root.element.querySelector(s"""[data-testid="$other"]""")).isEmpty &&
                  Option(dom.document.querySelector(s"""[data-testid="$tag"]""")).isEmpty
              )
            yield ok
          }

        for
          ra         <- Ref.make[Option[dom.Element]](None)
          rb         <- Ref.make[Option[dom.Element]](None)
          inA        <- Promise.make[Nothing, Unit]
          inB        <- Promise.make[Nothing, Unit]
          (okA, okB) <-
            scope("iso-a", "iso-b", ra, inA, inB).zipPar(scope("iso-b", "iso-a", rb, inB, inA))
          a <- ra.get
          b <- rb.get
        yield assertTrue(
          okA,
          okB,
          (a, b) match
            case (Some(x), Some(y)) => !x.ownerDocument.eq(y.ownerDocument)
            case _                  => false
          ,
          a.forall(!_.isConnected),
          b.forall(!_.isConnected),
        )
        end for
      },
      test("InputHandle.fill updates a Squawk bound to onInput") {
        for
          text <- sq("")
          ui = E.div(
            E.input(
              testId("todo"),
              A.typ("text"),
              A.value(text),
              Events.onInput(e => text.set(e.targetValue.getOrElse(""))),
            ),
            E.span(testId("echo"), text),
          )
          result <- withMounted(ui) { root =>
            for
              _    <- root.input("todo").fill("milk")
              echo <- waitForText(root, "echo", "milk")
              v    <- root.input("todo").value
            yield assertTrue(echo == "milk", v == "milk")
          }
        yield result
      },
      test("tag mismatch fails with a clear message") {
        val ui = E.div(E.button(testId("inc"), A.typ("button"), "Go"))
        withMounted(ui) { root =>
          root.input("inc").click.flip.map { err =>
            val msg = err.getMessage
            assertTrue(msg.contains("expected INPUT"), msg.contains("BUTTON"))
          }
        }
      },
      test("missing testid times out naming the selector") {
        withMounted(E.div()) { root =>
          root.button("nope").click.flip.map { err =>
            val msg = err.getMessage
            assertTrue(msg.contains("Timeout waiting for"), msg.contains("""button[data-testid="nope"]"""))
          }
        }
      },
    )

  private def waitForText(root: AscentRoot, testIdName: String, expected: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      root.getByTestId(testIdName).innerText.flatMap { t =>
        if t == expected then ZIO.succeed(t)
        else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testIdName == $expected"))(5.seconds)
end AscentChekhovSpec
