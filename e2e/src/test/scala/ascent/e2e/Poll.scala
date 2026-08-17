package ascent.e2e

import chekhov.ChekhovError
import zio.*

object Poll:

  def until[A](label: String)(io: IO[ChekhovError, A])(p: A => Boolean): IO[Throwable, A] =
    def loop: IO[Throwable, A] =
      io.foldZIO(
        {
          case ChekhovError.Timeout(msg) => ZIO.fail(new RuntimeException(msg))
          case _                         => ZIO.sleep(150.millis) *> loop
        },
        a => if p(a) then ZIO.succeed(a) else ZIO.sleep(150.millis) *> loop,
      )
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $label"))(20.seconds)
  end until
end Poll
