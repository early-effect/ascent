package ascent.docs

import ascent.*
import ascent.datastar.http.AscentDatastar
import ascent.dsl.*
import zio.*
import zio.http.{Method, Request, Response, Routes, Server, handler}
import zio.http.datastar.{Datastar, events}

/** Scope-bound in-process zio-http servers for Datastar DocSpec examples. */
object DocServers:

  final case class State(count: Ref[Int], pulse: Hub[Unit])

  /** Counter SSE + increment (mirrors example/datastar-app-server).
    *
    * The `Server` layer is provided around the whole effect so the bind stays up until `use` finishes (install alone
    * must not close the server).
    */
  def withCounter[R, E, A](use: String => ZIO[R, E, A]): ZIO[R & Scope, E | Throwable, A] =
    (for
      count <- Ref.make(0)
      pulse <- Hub.unbounded[Unit]
      state = State(count, pulse)
      port   <- Server.install(counterRoutes(state))
      result <- use(s"http://localhost:$port")
    yield result).provideSomeLayer[R & Scope](Server.defaultWith(_.port(0)))

  private def pushCount(state: State): ZIO[Datastar, Nothing, Unit] =
    state.count.get.flatMap(c => AscentDatastar.patchSignal("count", c))

  private def counterRoutes(state: State): Routes[Any, Nothing] =
    Routes(
      Method.GET / "sse" -> events {
        handler { (_: Request) =>
          for
            _      <- pushCount(state)
            stream <- state.pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
            _      <- stream.mapZIO(_ => pushCount(state)).runDrain
          yield ()
        }
      },
      Method.POST / "increment" -> handler { (_: Request) =>
        (state.count.update(_ + 1) *> state.pulse.publish(()).unit).as(Response.ok)
      },
    ).sandbox

  /** Minimal hybrid: SSE pushes a region; POST /send pulses. */
  def withHybridRegion[R, E, A](use: String => ZIO[R, E, A]): ZIO[R & Scope, E | Throwable, A] =
    (for
      pulse  <- Hub.unbounded[Unit]
      msgs   <- Ref.make(Vector("welcome"))
      port   <- Server.install(hybridRoutes(msgs, pulse))
      result <- use(s"http://localhost:$port")
    yield result).provideSomeLayer[R & Scope](Server.defaultWith(_.port(0)))

  private def pushMessages(msgs: Ref[Vector[String]]): ZIO[Datastar, Nothing, Unit] =
    msgs.get.flatMap { items =>
      AscentDatastar.patchRegion("messages", E.ul(items.map(s => E.li(s))*))
    }

  private def hybridRoutes(msgs: Ref[Vector[String]], pulse: Hub[Unit]): Routes[Any, Nothing] =
    Routes(
      Method.GET / "sse" -> events {
        handler { (_: Request) =>
          for
            _      <- pushMessages(msgs)
            stream <- pulse.subscribe.map(zio.stream.ZStream.fromQueue(_))
            _      <- stream.mapZIO(_ => pushMessages(msgs)).runDrain
          yield ()
        }
      },
      Method.POST / "send" -> handler { (_: Request) =>
        (msgs.update(_ :+ "ping") *> pulse.publish(()).unit).as(Response.ok)
      },
    ).sandbox
end DocServers
