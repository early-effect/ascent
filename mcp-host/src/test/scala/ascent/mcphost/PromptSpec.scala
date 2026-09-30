package ascent.mcphost

import ascent.{Source, sq}
import heddle.mcp.apps.UiUri
import heddle.mcp.apps.frame.FrameError
import heddle.mcp.apps.host.{ConsentOutcome, ConsentRequest, Ending, ServerName}
import heddle.mcp.protocol.ToolName
import zio.*
import zio.json.ast.Json
import zio.test.*

/** The question one view asks: one call at a time, answered only by a button that has armed, and withdrawn when the
  * view stops. Time is the test clock's.
  */
object PromptSpec extends ZIOSpecDefault:
  private val armAfter = 1.second

  private val inc   = ToolName("inc")
  private val reset = ToolName("reset")

  private def request(tool: ToolName) =
    ConsentRequest(ServerName("counter"), UiUri("ui://counter/view"), tool, Json.Obj())

  private val choices =
    Gen.elements(ConsentOutcome.AllowOnce, ConsentOutcome.AllowForSession, ConsentOutcome.Rejected)

  private val quiet = Gen.elements(
    ViewState.Detached,
    ViewState.Leaving,
    ViewState.Ended(Ending.PortClosed),
    ViewState.Removed,
    ViewState.Failed(FrameError.RelayNeverReady(1.second)),
  )

  private def prompting(from: ViewState) =
    sq[ViewState](from).flatMap(states => Prompt.make(states, armAfter).map((states, _)))

  /** The first state `p` accepts: the current one, or the first change to one. */
  private def until(states: Source[ViewState])(p: ViewState => Boolean): UIO[ViewState] =
    Promise
      .make[Nothing, ViewState]
      .flatMap: reached =>
        def offer(s: ViewState): UIO[Unit] = reached.succeed(s).when(p(s)).unit
        ZIO.scoped(ZIO.acquireRelease(states.observe(offer))(_.cancel) *> states.get.flatMap(offer) *> reached.await)

  private def asked(tool: ToolName)(s: ViewState): Boolean = s == ViewState.Asking(request(tool))

  def spec = suite("Prompt")(
    test("an answer before the buttons arm is no answer; once they arm, the user's answer is the call's") {
      check(choices, choices) { (early, chosen) =>
        for
          (states, prompt) <- prompting(ViewState.Serving)
          call             <- prompt.ask(request(inc)).fork
          shown            <- until(states)(asked(inc))
          _                <- prompt.answer(early)
          _                <- TestClock.adjust(armAfter.minusMillis(1))
          _                <- prompt.answer(early)
          unarmed          <- prompt.armed.get
          _                <- TestClock.adjust(1.milli)
          armed            <- prompt.armed.get
          _                <- prompt.answer(chosen)
          outcome          <- call.join
          after            <- states.get
          disarmed         <- prompt.armed.get
        yield assertTrue(
          shown == ViewState.Asking(request(inc)),
          !unarmed,
          armed,
          outcome == chosen,
          after == ViewState.Serving,
          !disarmed,
        )
      }
    },
    test("a withdrawn question is answered Cancelled, and the view goes back to serving") {
      for
        (states, prompt) <- prompting(ViewState.Serving)
        call             <- prompt.ask(request(inc)).fork
        _                <- until(states)(asked(inc))
        _                <- prompt.withdraw
        outcome          <- call.join
        after            <- states.get
      yield assertTrue(outcome == ConsentOutcome.Cancelled, after == ViewState.Serving)
    },
    test("a view that cannot ask is answered Cancelled, and shows no question") {
      check(quiet) { state =>
        for
          (states, prompt) <- prompting(state)
          outcome          <- prompt.ask(request(inc))
          after            <- states.get
        yield assertTrue(outcome == ConsentOutcome.Cancelled, after == state)
      }
    },
    test("one question at a time: the next call shows once the first is answered, and its buttons arm afresh") {
      for
        (states, prompt) <- prompting(ViewState.Serving)
        first            <- prompt.ask(request(inc)).fork
        _                <- until(states)(asked(inc))
        second           <- prompt.ask(request(reset)).fork
        _                <- TestClock.adjust(armAfter)
        waiting          <- states.get
        _                <- prompt.answer(ConsentOutcome.AllowOnce)
        one              <- first.join
        _                <- until(states)(asked(reset))
        _                <- prompt.answer(ConsentOutcome.AllowOnce)
        _                <- TestClock.adjust(armAfter)
        _                <- prompt.answer(ConsentOutcome.Rejected)
        two              <- second.join
      yield assertTrue(
        waiting == ViewState.Asking(request(inc)),
        one == ConsentOutcome.AllowOnce,
        two == ConsentOutcome.Rejected,
      )
    },
  ) @@ TestAspect.timeout(60.seconds)
end PromptSpec
