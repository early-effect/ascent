package example.chat

import zio.schema.*

/** A chat message. The server owns these; it renders them to HTML (via ascent-html) and pushes the rendered list into
  * the client's server region.
  */
final case class Message(id: String, username: String, content: String, timestamp: Long)

object Message:
  def make(username: String, content: String, now: Long): Message =
    Message(java.util.UUID.randomUUID().toString, username, content, now)

/** The signal payloads the CLIENT posts and the server reads via `readSignals`. The client binds its inputs to signals
  * of the same names (`username`, `message`), so the server decodes them directly.
  */
final case class MessageRequest(username: String, message: String) derives Schema
final case class TypingRequest(username: String) derives Schema
final case class JoinRequest(username: String) derives Schema
