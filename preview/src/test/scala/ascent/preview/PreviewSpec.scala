package ascent.preview

import heddle.*
import zio.*
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
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root))
          body <- resp.body.utf8
        yield assertTrue(resp.status.isSuccess, body.contains("home"))
      },
      test("directory without index is not success") {
        for
          tmp  <- tempSite("assets/theme.css" -> "body{}")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / "assets"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("serves index.html from the site root") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html>ok</html>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / "index.html"))
          body <- resp.body.utf8
        yield assertTrue(resp.status.isSuccess, body.contains("ok"))
      },
      test("serves nested assets") {
        for
          tmp <- tempSite(
            "index.html"       -> "<html/>",
            "assets/theme.css" -> "body{color:red}",
          )
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / "assets" / "theme.css"))
          body <- resp.body.utf8
        yield assertTrue(resp.status.isSuccess, body.contains("color:red"))
      },
      test("missing file is not success") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / "missing.html"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("rejects path traversal") {
        for
          tmp  <- tempSite("index.html" -> "<html>ok</html>")
          resp <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / ".." / "etc" / "passwd"))
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
            .runZIO(Request.get(Url.root / ".." / "site-evil" / "secret.html"))
        yield assertTrue(!resp.status.isSuccess)
      },
      test("CORS header is absent by default") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview
            .routes(PreviewConfig(tmp))
            .runZIO(Request.get(Url.root).addHeader(Header.origin("http", "example.com")))
        yield assertTrue(resp.header(HeaderName.AccessControlAllowOrigin).isEmpty)
      },
      test("CORS header is present when enabled") {
        for
          tmp  <- tempSite("index.html" -> "<html/>")
          resp <- Preview
            .routes(PreviewConfig(tmp, cors = true))
            .runZIO(Request.get(Url.root).addHeader(Header.origin("http", "example.com")))
        yield assertTrue(resp.header(HeaderName.AccessControlAllowOrigin).isDefined)
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
          resp  <- Preview.routes(PreviewConfig(tmp)).runZIO(Request.get(Url.root / "__ascent" / "reload"))
        yield assertTrue(
          chunk.isEmpty,
          resp.status.isSuccess,
          resp.header(HeaderName.ContentType).exists(_.contains("text/event-stream")),
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
    suite("browse command")(
      test("macOS uses open") {
        assertTrue(Preview.browseCommand("Mac OS X", "http://localhost:8765/") == Seq("open", "http://localhost:8765/"))
      },
      test("Windows uses rundll32") {
        assertTrue(
          Preview.browseCommand("Windows 11", "http://localhost:8765/") ==
            Seq("rundll32", "url.dll,FileProtocolHandler", "http://localhost:8765/")
        )
      },
      test("Linux uses xdg-open") {
        assertTrue(
          Preview.browseCommand("Linux", "http://localhost:8765/") == Seq("xdg-open", "http://localhost:8765/")
        )
      },
    ),
    suite("CLI args")(
      test("defaults port and root") {
        val c = PreviewMain.configFromArgs(Chunk.empty)
        assertTrue(c.port == 8765, !c.openBrowser, c.root.endsWith("target/site"))
      },
      test("--open is independent of position") {
        val a    = PreviewMain.configFromArgs(Chunk("--open", "9000", "/tmp/site"))
        val b    = PreviewMain.configFromArgs(Chunk("9000", "/tmp/site", "--open"))
        val root = Path.of("/tmp/site")
        assertTrue(
          a.openBrowser,
          b.openBrowser,
          a.port == 9000,
          b.port == 9000,
          a.root == root,
          b.root == root,
        )
      },
      test("omitted --open leaves the browser closed") {
        val c = PreviewMain.configFromArgs(Chunk("8765", "/tmp/site"))
        assertTrue(!c.openBrowser, c.port == 8765)
      },
    ),
    suite("live server")(
      test("Client can fetch a page from an installed server") {
        for
          tmp <- tempSite("hello.html" -> "<html>hello</html>")
          routes = Preview.routes(PreviewConfig(tmp))
          result <- ZIO
            .scoped {
              Server.install(routes).flatMap { server =>
                server.port.flatMap { port =>
                  Client.get(s"http://127.0.0.1:$port/hello.html").flatMap { resp =>
                    resp.body.utf8.map(body => assertTrue(resp.status.isSuccess, body.contains("hello")))
                  }
                }
              }
            }
            .provide(Server.defaultWith(_.port(0)))
        yield result
      } @@ TestAspect.withLiveClock @@ TestAspect.timeout(10.seconds)
    ),
    suite("serve")(
      test("extra routes are reachable and static still serves") {
        for
          tmp   <- tempSite("index.html" -> "<html>home</html>")
          port  <- freePort
          fiber <- Preview
            .serve(PreviewConfig(tmp, port = port), extraRoutes = ping)
            .provideSome[Scope](Server.defaultWith(_.port(port)))
            .fork
          pingR <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          pingB <- pingR.body.utf8
          homeR <- Client.get(s"http://127.0.0.1:$port/")
          homeB <- homeR.body.utf8
          _     <- fiber.interrupt
        yield assertTrue(pingR.status.isSuccess, pingB == "pong", homeB.contains("home"))
      },
      test("interrupting serve runs sidecar finalizer") {
        for
          tmp   <- tempSite("index.html" -> "<html/>")
          flag  <- Ref.make(false)
          port  <- freePort
          fiber <- ZIO
            .scoped(
              Preview.serve(PreviewConfig(tmp, port = port), sidecar = ZIO.addFinalizer(flag.set(true)))
            )
            .provide(Server.defaultWith(_.port(port)))
            .fork
          _ <- getUntilOk(s"http://127.0.0.1:$port/")
          _ <- fiber.interrupt
          v <- flag.get
        yield assertTrue(v)
      },
      test("sidecar and extra routes run together") {
        for
          tmp   <- tempSite("index.html" -> "<html/>")
          flag  <- Ref.make(false)
          port  <- freePort
          fiber <- ZIO
            .scoped(
              Preview.serve(
                PreviewConfig(tmp, port = port),
                sidecar = ZIO.addFinalizer(flag.set(true)),
                extraRoutes = ping,
              )
            )
            .provide(Server.defaultWith(_.port(port)))
            .fork
          pingR <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          body  <- pingR.body.utf8
          _     <- fiber.interrupt
          v     <- flag.get
        yield assertTrue(pingR.status.isSuccess, body == "pong", v)
      },
      test("CORS header is present on extra routes when enabled") {
        for
          tmp   <- tempSite("index.html" -> "<html/>")
          port  <- freePort
          fiber <- Preview
            .serve(PreviewConfig(tmp, port = port, cors = true), extraRoutes = ping)
            .provideSome[Scope](Server.defaultWith(_.port(port)))
            .fork
          _    <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          resp <- Client.request(
            Method.GET,
            s"http://127.0.0.1:$port/api/ping",
            Headers.empty.add(Header.origin("http", "example.com")),
          )
          _ <- fiber.interrupt
        yield assertTrue(resp.header(HeaderName.AccessControlAllowOrigin).isDefined)
      },
      test("restartSidecarOnStamp finalizes the previous sidecar") {
        for
          tmp     <- tempSite("index.html" -> "<html/>")
          started <- Ref.make(0)
          stopped <- Ref.make(0)
          port    <- freePort
          sidecar =
            started.update(_ + 1) *>
              ZIO.addFinalizer(stopped.update(_ + 1)) *>
              ZIO.never
          fiber <- Preview
            .serve(
              PreviewConfig(tmp, port = port),
              sidecar = sidecar,
              extraRoutes = ping,
              restartSidecarOnStamp = true,
            )
            .provideSome[Scope](Server.defaultWith(_.port(port)))
            .fork
          _ <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          _ <- started.get.repeatUntil(_ >= 1)
          stamp = tmp.resolve("assets/dev-stamp")
          _ <- ZIO.attempt {
            Files.createDirectories(stamp.getParent)
            Files.writeString(stamp, "1", StandardCharsets.UTF_8)
          }
          _ <- ZIO.sleep(150.millis)
          _ <- ZIO.attempt(Files.writeString(stamp, "2", StandardCharsets.UTF_8))
          _ <- started.get.repeatUntil(_ >= 2).timeoutFail(RuntimeException("sidecar did not restart"))(3.seconds)
          _ <- stopped.get
            .repeatUntil(_ >= 1)
            .timeoutFail(RuntimeException("previous sidecar did not finalize"))(3.seconds)
          _ <- fiber.interrupt
          s <- started.get
          k <- stopped.get
        yield assertTrue(s >= 2, k >= 1)
      },
      test("restartSidecarOnStamp re-runs sidecar when stamp bytes change") {
        for
          tmp   <- tempSite("index.html" -> "<html/>")
          count <- Ref.make(0)
          port  <- freePort
          fiber <- Preview
            .serve(
              PreviewConfig(tmp, port = port),
              sidecar = count.update(_ + 1),
              extraRoutes = ping,
              restartSidecarOnStamp = true,
            )
            .provideSome[Scope](Server.defaultWith(_.port(port)))
            .fork
          _ <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          _ <- count.get.repeatUntil(_ >= 1)
          stamp = tmp.resolve("assets/dev-stamp")
          _ <- ZIO.attempt {
            Files.createDirectories(stamp.getParent)
            Files.writeString(stamp, "1", StandardCharsets.UTF_8)
          }
          _     <- ZIO.sleep(150.millis)
          _     <- ZIO.attempt(Files.writeString(stamp, "2", StandardCharsets.UTF_8))
          n     <- count.get.repeatUntil(_ >= 2).timeoutFail(RuntimeException("sidecar did not restart"))(3.seconds)
          pingR <- getUntilOk(s"http://127.0.0.1:$port/api/ping")
          _     <- fiber.interrupt
        yield assertTrue(n >= 2, pingR.status.isSuccess)
      },
    ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(15.seconds),
    test("sidecar failure fails serve and unbinds when Server is provided with serve") {
      for
        tmp  <- tempSite("index.html" -> "<html/>")
        port <- freePort
        exit <- Preview
          .serve(PreviewConfig(tmp, port = port), sidecar = ZIO.fail(RuntimeException("sidecar")))
          .provideSome[Scope](Server.defaultWith(_.port(port)))
          .exit
        free <- ZIO.attempt {
          val ss = java.net.ServerSocket(port)
          ss.close()
          true
        }
      yield assertTrue(exit.isFailure, free)
    } @@ TestAspect.timeout(10.seconds),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

  private val ping: Routes[Any, Response] =
    Routes(Method.GET / "api" / "ping" -> Handler.text("pong"))

  private def freePort: Task[Int] =
    ZIO.attempt {
      val ss = java.net.ServerSocket(0)
      val p  = ss.getLocalPort
      ss.close()
      p
    }

  private def getUntilOk(url: String): Task[Response] =
    Client
      .get(url)
      .flatMap { resp =>
        if resp.status.isSuccess then ZIO.succeed(resp)
        else ZIO.fail(RuntimeException(s"${resp.status} $url"))
      }
      .retry(Schedule.spaced(50.millis) && Schedule.recurs(80))

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
