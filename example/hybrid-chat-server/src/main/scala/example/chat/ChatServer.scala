package example.chat

import ascent.datastar.http.AscentDatastar
import zio.*
import zio.http.*
import zio.http.datastar.*

/** The hybrid-chat backend. It owns the message list and drives the client's `serverRegion("messages")` — "the chat
  * interaction itself, server-side" — while the rest of the UI is normal client ascent.
  *
  *   - `GET /chat/sse` opens a datastar stream: push the message region + a `typing` signal now, then again on every
  *     change (the chat-example Hub pattern). The messages are rendered from ascent's typed `UI` via
  *     [[AscentDatastar.patchRegion]]; the typing indicator rides the signal channel.
  *   - `POST /chat/send` reads the client's `{username, message}` signals and appends a message.
  *   - `POST /chat/typing` marks the user typing (auto-cleared after a few seconds).
  */
object ChatServer extends ZIOAppDefault:

  /** Push the message region (HTML) + the typing signal (excluding `me`). */
  private def pushState(room: ChatRoom, me: String): ZIO[Datastar, Nothing, Unit] =
    for
      msgs   <- ChatRoom.getMessages(room)
      typing <- ChatRoom.getTyping(room)
      _      <- AscentDatastar.patchRegion("messages", MessageView.list(msgs))
      others = (typing - me).toList.sorted
      label  = others match
        case Nil      => ""
        case h :: Nil => s"$h is typing…"
        case many     => s"${many.mkString(", ")} are typing…"
      _ <- AscentDatastar.patchSignal("typing", label)
    yield ()

  private def routes(room: ChatRoom): Routes[Any, Nothing] =
    Routes(
      Method.GET / "chat" / "sse" -> events {
        handler { (req: Request) =>
          for
            join   <- req.readSignals[JoinRequest].orElseSucceed(JoinRequest(""))
            _      <- pushState(room, join.username)
            stream <- ChatRoom.subscribe(room)
            _      <- stream.mapZIO(_ => pushState(room, join.username)).runDrain
          yield ()
        }
      },
      Method.POST / "chat" / "send" -> handler { (req: Request) =>
        (for
          rq <- req.readSignals[MessageRequest].orElseSucceed(MessageRequest("", ""))
          _  <- ZIO
            .clockWith(_.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .flatMap(now => ChatRoom.addMessage(room, Message.make(rq.username, rq.message, now)))
            .when(rq.username.trim.nonEmpty && rq.message.trim.nonEmpty)
          _ <- ChatRoom.clearTyping(room, rq.username)
        yield Response.ok)
      },
      Method.POST / "chat" / "typing" -> handler { (req: Request) =>
        (for
          rq <- req.readSignals[TypingRequest].orElseSucceed(TypingRequest(""))
          _  <- ChatRoom.setTyping(room, rq.username).when(rq.username.trim.nonEmpty)
          // Auto-clear after a quiet period (fire-and-forget, like the reference app).
          _ <- ChatRoom.clearTyping(room, rq.username).delay(3.seconds).forkDaemon
        yield Response.ok)
      },
    ).sandbox

  private val compression =
    Server.Config.ResponseCompressionConfig(
      contentThreshold = 0,
      options = IndexedSeq(
        Server.Config.CompressionOptions.brotli(quality = 8, lgwin = 24),
        Server.Config.CompressionOptions.gzip(),
      ),
    )

  def run =
    for
      room <- ChatRoom.make
      _    <- ZIO.logInfo("hybrid-chat server on http://localhost:8080 (run Vite for the client)")
      _    <- Server
        .serve(routes(room))
        .provide(Server.defaultWith(_.port(8080).copy(responseCompression = Some(compression))))
    yield ()
end ChatServer
