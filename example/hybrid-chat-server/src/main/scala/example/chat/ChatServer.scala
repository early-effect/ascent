package example.chat

import ascent.datastar.http.AscentDatastar
import ascent.preview.{Preview, PreviewConfig}
import zio.*
import zio.http.*
import zio.http.datastar.*

import java.nio.file.Path as JPath

/** The hybrid-chat backend. It owns the message list and drives the client's `serverRegion("messages")` — "the chat
  * interaction itself, server-side" — while the rest of the UI is normal client ascent.
  *
  *   - `GET /chat/sse` opens a datastar stream: push the message region + a `typing` signal now, then again on every
  *     change (the chat-example Hub pattern). The messages are rendered from ascent's typed `UI` via
  *     [[AscentDatastar.patchRegion]]; the typing indicator rides the signal channel.
  *   - `POST /chat/send` reads the client's `{username, message}` signals and appends a message.
  *   - `POST /chat/typing` marks the user typing (auto-cleared after a few seconds).
  *   - [[Preview.serve]] takes the API as `extraRoutes` so the spliced client (`example/hybrid-chat/target/preview`) is
  *     same-origin on `:8080`.
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

  def apiRoutes(room: ChatRoom): Routes[Any, Nothing] =
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

  /** Preview (static + SSE reload) composed with the chat API. Extra routes first so `/chat/sse` wins the trailing GET.
    */
  def routes(room: ChatRoom, previewRoot: JPath, port: Int = 8080): Routes[Any, Response] =
    apiRoutes(room) ++ Preview.routes(PreviewConfig(root = previewRoot, port = port))

  def resolvePreviewRoot(args: Chunk[String]): JPath =
    args.headOption
      .map(JPath.of(_))
      .getOrElse(JPath.of("example/hybrid-chat/target/preview"))
      .toAbsolutePath
      .normalize

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
      args <- getArgs
      root = resolvePreviewRoot(args)
      room <- ChatRoom.make
      _    <- ZIO.logInfo(s"hybrid-chat on http://localhost:8080 serving $root")
      _    <- Preview
        .serve(PreviewConfig(root = root, port = 8080), extraRoutes = apiRoutes(room))
        .provideSome[Scope](
          Server.defaultWith(_.port(8080).copy(responseCompression = Some(compression)))
        )
    yield ()
end ChatServer
