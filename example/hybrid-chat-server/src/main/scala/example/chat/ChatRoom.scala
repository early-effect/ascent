package example.chat

import zio.*
import zio.stream.*

/** In-memory chat state with a `Hub` that pulses on every change, so an open SSE stream can re-push the message region
  * + typing indicator. Mirrors the reference zio-http-datastar-chat ChatRoom.
  */
final case class ChatRoom(
    messages: Ref[List[Message]],
    typingUsers: Ref[Set[String]],
    hub: Hub[Unit],
)

object ChatRoom:
  def make: UIO[ChatRoom] =
    for
      messages <- Ref.make(List.empty[Message])
      typing   <- Ref.make(Set.empty[String])
      hub      <- Hub.unbounded[Unit]
    yield ChatRoom(messages, typing, hub)

  private def changed(room: ChatRoom): UIO[Unit] = room.hub.publish(()).unit

  def addMessage(room: ChatRoom, msg: Message): UIO[Unit] =
    room.messages.update(_ :+ msg) *> changed(room)

  def getMessages(room: ChatRoom): UIO[List[Message]] = room.messages.get

  def setTyping(room: ChatRoom, user: String): UIO[Unit] =
    room.typingUsers.update(_ + user) *> changed(room)

  def clearTyping(room: ChatRoom, user: String): UIO[Unit] =
    room.typingUsers.update(_ - user) *> changed(room)

  def getTyping(room: ChatRoom): UIO[Set[String]] = room.typingUsers.get

  def subscribe(room: ChatRoom): ZIO[Scope, Nothing, UStream[Unit]] =
    room.hub.subscribe.map(ZStream.fromQueue(_))
end ChatRoom
