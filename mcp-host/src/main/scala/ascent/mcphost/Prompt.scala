package ascent.mcphost

import ascent.{Source, Squawk, sq}
import heddle.mcp.apps.host.{ConsentOutcome, ConsentRequest}
import zio.*

/** One view's consent question. It asks one call at a time, shows it on the view's element, and takes an answer only
  * from that element's buttons, and only once they have been armed for `armAfter`: a click the user aimed at the view
  * cannot land on "Allow" as the question appears.
  */
final private[mcphost] class Prompt private (
    states: Source[ViewState],
    pending: Ref[Option[Promise[Nothing, ConsentOutcome]]],
    armedNow: Source[Boolean],
    turn: Semaphore,
    armAfter: Duration,
):
  /** Whether the buttons take an answer yet. */
  def armed: Squawk[Boolean] = armedNow

  def ask(request: ConsentRequest): UIO[ConsentOutcome] =
    turn.withPermit(
      ZIO.ifZIO(states.get.map(_.live))(
        ZIO.scoped(
          for
            answer <- Promise.make[Nothing, ConsentOutcome]
            _      <- ZIO.acquireRelease(pending.set(Some(answer)) *> armedNow.set(false) *> show(request))(_ =>
              pending.set(None) *> armedNow.set(false) *> states.update(Prompt.answered)
            )
            _       <- (ZIO.sleep(armAfter) *> armedNow.set(true)).forkScoped
            outcome <- answer.await
          yield outcome
        ),
        ZIO.succeed(ConsentOutcome.Cancelled),
      )
    )

  /** The element's buttons answer through here. A click before the buttons arm is not an answer. */
  def answer(outcome: ConsentOutcome): UIO[Unit] =
    ZIO.whenZIODiscard(armedNow.get)(pending.get.flatMap(ZIO.foreachDiscard(_)(_.succeed(outcome))))

  /** The view stopped mid-question, so nobody answered it. */
  def withdraw: UIO[Unit] = pending.get.flatMap(ZIO.foreachDiscard(_)(_.succeed(ConsentOutcome.Cancelled)))

  private def show(request: ConsentRequest): UIO[Unit] =
    states.update(s => if s.live then ViewState.Asking(request) else s)
end Prompt

private[mcphost] object Prompt:
  def make(states: Source[ViewState], armAfter: Duration): UIO[Prompt] =
    for
      pending <- Ref.make(Option.empty[Promise[Nothing, ConsentOutcome]])
      armed   <- sq(false)
      turn    <- Semaphore.make(1)
    yield Prompt(states, pending, armed, turn, armAfter)

  private def answered(s: ViewState): ViewState = s match
    case ViewState.Asking(_) => ViewState.Serving
    case other               => other
end Prompt
