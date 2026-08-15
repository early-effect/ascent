package ascent.preview

import zio.*
import zio.http.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object PreviewSpec extends ZIOSpecDefault:

  def spec = suite("Preview")(
    suite("routes")(
      test("serves index.html for the site root path") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html>home</html>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("home"))
      },
      test("directory without index is not success") {
        for
          tmp  <- tempSite("assets/theme.css" -> "body{}")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / "assets"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("serves index.html from the site root") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html>ok</html>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / "index.html"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("ok"))
      },
      test("serves nested assets") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html/>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / "assets" / "theme.css"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("color:red"))
      },
      test("missing file is not success") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / "missing.html"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("rejects path traversal") {
        for
          tmp  <- tempSite("index.html" -> "<html>ok</html>")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / ".." / "etc" / "passwd"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("sibling prefix of site root is not served") {
        for
          parent <- ZIO.attempt(Files.createTempDirectory("ascent-preview-parent"))
          site = parent.resolve("site")
          evil = parent.resolve("site-evil")
          _ <- ZIO.attempt {
            Files.createDirectories(site)
            Files.createDirectories(evil)
            Files.writeString(site.resolve("index.html"), "<html>site</html>", StandardCharsets.UTF_8)
            Files.writeString(evil.resolve("secret.html"), "<html>secret</html>", StandardCharsets.UTF_8)
          }
          resp <- Preview
            .routes(PreviewConfig(site))
            .runZIO(
              Request.get(URL.root / ".." / "site-evil" / "secret.html")
            )
        yield assertTrue(!resp.status.isSuccess)
      },
      test("CORS header is absent by default") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview
            .routes(PreviewConfig(tmp))
            .runZIO(
              Request.get(URL.root).addHeader(Header.Origin("http", "example.com", None))
            )
        yield assertTrue(resp.headers.get(Header.AccessControlAllowOrigin).isEmpty)
      },
      test("CORS header is present when enabled") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview
            .routes(PreviewConfig(tmp, cors = true))
            .runZIO(Request.get(URL.root).addHeader(Header.Origin("http", "example.com", None)))
        yield assertTrue(resp.headers.get(Header.AccessControlAllowOrigin).isDefined)
      },
    ),
    suite("SSE stamp")(
      test("subscribe does not emit before the stamp changes") {
        for
          tmp <- tempSite("index.html" -> "<html/>")
          stamp = tmp.resolve("assets/dev-stamp")
          _ <- ZIO.attempt {
            Files.createDirectories(stamp.getParent)
            Files.writeString(stamp, "1", StandardCharsets.UTF_8)
          }
          chunk <- Preview.stampEvents(stamp).take(1).runCollect.timeout(250.millis)
          resp  <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(URL.root / "__ascent" / "reload"))
        yield assertTrue(
          chunk.isEmpty,
          resp.status.isSuccess,
          resp.headers.get(Header.ContentType).exists(_.renderedValue.contains("text/event-stream")),
        )
      },
      test("rewriting stamp bytes emits a reload event") {
        for
          tmp <- tempSite("index.html" -> "<html/>")
          stamp = tmp.resolve("assets/dev-stamp")
          _ <- ZIO.attempt {
            Files.createDirectories(stamp.getParent)
            Files.writeString(stamp, "1", StandardCharsets.UTF_8)
          }
          fiber <- Preview.stampEvents(stamp).take(1).runCollect.fork
          _     <- ZIO.sleep(150.millis)
          _     <- ZIO.attempt(Files.writeString(stamp, "2", StandardCharsets.UTF_8))
          evs   <- fiber.join.timeoutFail(new RuntimeException("stamp rewrite did not emit"))(3.seconds)
        yield assertTrue(evs.headOption.exists(_.data == "reload"))
      },
    ),
    suite("live server")(
      test("Client can fetch a page from an installed server") {
        for
          tmp <- tempSite("hello.html" -> "<html>hello</html>")
          routes = Preview.routes(PreviewConfig(tmp))
          port <- Server.install(routes)
          resp <- ZClient.batched(Request.get(url"http://127.0.0.1:$port/hello.html"))
          body <- resp.body.asString
        yield assertTrue(resp.status.isSuccess, body.contains("hello"))
      }
        .provide(Server.defaultWith(_.onAnyOpenPort), Client.default) @@
        TestAspect.withLiveClock @@
        TestAspect.timeout(10.seconds)
    ),
  ) @@ TestAspect.withLiveClock

  private def tempSite(files: (String, String)*): Task[Path] =
    ZIO.attempt {
      val tmp = Files.createTempDirectory("ascent-preview")
      files.foreach { case (rel, content) =>
        val path = tmp.resolve(rel)
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, content, StandardCharsets.UTF_8)
      }
      tmp
    }
end PreviewSpec
