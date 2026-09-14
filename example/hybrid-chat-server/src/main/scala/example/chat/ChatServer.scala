package example.chat

import ascent.datastar.http.AscentDatastar
import ascent.preview.{Preview, PreviewConfig}
import heddle.*
import heddle.brotli.Brotli
import heddle.datastar.{Datastar, events, readSignals}
import heddle.json.given
import zio.*

import java.nio.file.Path as JPath

object ChatServer extends ZIOAppDefault:

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
      Method.GET / "chat" / "sse" -> events(handler { (req: Request) =>
        (for
          join   <- req.readSignals[JoinRequest].orElseSucceed(JoinRequest(""))
          _      <- pushState(room, join.username)
          stream <- ChatRoom.subscribe(room)
          _      <- stream.mapZIO(_ => pushState(room, join.username)).runDrain
        yield ()).as(Response.ok)
      }),
      Method.POST / "chat" / "send" -> handler { (req: Request) =>
        for
          rq <- req.readSignals[MessageRequest].orElseSucceed(MessageRequest("", ""))
          _  <- ZIO
            .clockWith(_.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .flatMap(now => ChatRoom.addMessage(room, Message.make(rq.username, rq.message, now)))
            .when(rq.username.trim.nonEmpty && rq.message.trim.nonEmpty)
          _ <- ChatRoom.clearTyping(room, rq.username)
        yield Response.ok
      },
      Method.POST / "chat" / "typing" -> handler { (req: Request) =>
        for
          rq <- req.readSignals[TypingRequest].orElseSucceed(TypingRequest(""))
          _  <- ChatRoom.setTyping(room, rq.username).when(rq.username.trim.nonEmpty)
          _  <- ChatRoom.clearTyping(room, rq.username).delay(3.seconds).forkDaemon
        yield Response.ok
      },
    )

  def routes(room: ChatRoom, previewRoot: JPath, port: Int = 8080): Routes[Any, Response] =
    apiRoutes(room) ++ Preview.routes(PreviewConfig(root = previewRoot, port = port))

  def resolvePreviewRoot(args: Chunk[String]): JPath =
    args.headOption
      .map(JPath.of(_))
      .getOrElse(JPath.of("example/hybrid-chat/target/preview"))
      .toAbsolutePath
      .normalize

  def run =
    for
      args <- getArgs
      root = resolvePreviewRoot(args)
      room <- ChatRoom.make
      _    <- ZIO.logInfo(s"hybrid-chat on http://localhost:8080 serving $root")
      _    <- Preview
        .serve(PreviewConfig(root = root, port = 8080), extraRoutes = apiRoutes(room))
        .provideSome[Scope](
          Server.defaultWith(_.port(8080).copy(compressors = Chunk(Compressor.gzip, Brotli.compressor)))
        )
    yield ()
end ChatServer
