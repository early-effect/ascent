package ascent.e2e

import chekhov.*
import example.chat.{ChatRoom, ChatServer}
import zio.*
import zio.test.*

object HybridChatSpec extends AscentChekhovSuite:

  def spec =
    suite("hybrid-chat")(
      test("sending a message updates the server region") {
        (for
          room <- ChatRoom.make
          base <- PreviewServe.install(ChatServer.routes(room, Repo.preview("hybrid-chat")))
          page <- Chekhov.page
          _    <- page.goto(base.value + "/")
          _    <- page.getByPlaceholder("Your name").fill("Ada")
          _    <- page.getByPlaceholder("Type a message and press Enter").fill("hello from e2e")
          _    <- page.getByRole(Role.Button, name = Some("Send")).click
          body <- Poll.until("message appears")(page.innerText("#messages"))(_.contains("hello from e2e"))
        yield assertTrue(body.contains("hello from e2e"), body.contains("Ada")))
          .tapError(_ => screenshot("hybrid-chat"))
          .provide(PreviewServe.serverLayer.orDie, chekhovLayer)
      }
    )
end HybridChatSpec
