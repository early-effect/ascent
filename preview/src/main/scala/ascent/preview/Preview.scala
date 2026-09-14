package ascent.preview

import heddle.*
import heddle.sse.{ServerSentEvent, Sse}
import zio.*
import zio.stream.*

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.jdk.CollectionConverters.*

/** Serves a static directory with a path jail, plus an SSE reload endpoint that fires when a stamp file's **contents**
  * change.
  *
  * [[routes]] is the testable surface (no sockets). [[serve]] is the scoped process wrapper used by [[PreviewMain]] and
  * sbt-ascent-preview: extra routes are composed in front of the static trailing GET so `/__ascent/reload` is never
  * mistaken for a file, and an optional sidecar effect runs beside the HTTP server in the caller's `Scope`.
  */
object Preview:

  /** Build routes for `config` without starting a server. */
  def routes(config: PreviewConfig): Routes[Any, Response] =
    withCors(config, staticAndReload(config))

  /** Serve `config.root` until interruption.
    *
    * Provide `Server.Config` at the call site (`Server.defaultWith(_.port(config.port))`).
    */
  def serve(
      config: PreviewConfig,
      sidecar: ZIO[Scope, Throwable, Any] = ZIO.unit,
      extraRoutes: Routes[Any, Response] = Routes.empty,
      restartSidecarOnStamp: Boolean = false,
  ): ZIO[Scope & Server.Config, Throwable, Nothing] =
    val app  = withCors(config, extraRoutes ++ staticAndReload(config))
    val side =
      if restartSidecarOnStamp then restartSidecar(config, sidecar)
      else sidecar
    side.zipParRight(installAndHang(config, app))
  end serve

  /** `serve` with `Server.defaultWith(_.port(config.port))`. For `ZIOAppDefault` entry points. */
  def serveForever(config: PreviewConfig): ZIO[Scope, Throwable, Nothing] =
    serve(config).provideSome[Scope](Server.defaultWith(_.port(config.port)))

  private def staticAndReload(config: PreviewConfig): Routes[Any, Response] =
    val docRoot = config.root.toAbsolutePath.normalize.toFile
    Routes(
      Method.GET / trailing -> { (path: Path, _: Request) =>
        if isReload(path, config.reloadPath) then
          ZIO.succeed(Sse.response(stampEvents(config.root.resolve(config.stamp))))
        else
          resolveFile(docRoot, path) match
            case Some(file) =>
              heddle.Files.fromPath(file.toPath).catchAll {
                case _: java.nio.file.NoSuchFileException   => ZIO.succeed(Response.notFound())
                case _: java.nio.file.AccessDeniedException => ZIO.succeed(Response.empty(Status.Forbidden))
                case _                                      => ZIO.succeed(Response.notFound())
              }
            case None => ZIO.succeed(Response.notFound())
      }
    )
  end staticAndReload

  private def withCors(config: PreviewConfig, app: Routes[Any, Response]): Routes[Any, Response] =
    if config.cors then app @@ Middleware.cors() else app

  private def installAndHang(
      config: PreviewConfig,
      app: Routes[Any, Response],
  ): ZIO[Scope & Server.Config, Throwable, Nothing] =
    val dir = config.root.toAbsolutePath.normalize.toFile
    Server
      .install(app)
      .mapError(e => RuntimeException(e.message))
      .flatMap { server =>
        server.port.flatMap { bound =>
          val url = s"http://localhost:$bound/"
          Console.printLine(s"Serving ${dir.getAbsolutePath}") *>
            Console.printLine(s"Open $url") *>
            ZIO.when(config.openBrowser)(openBrowser(url)) *>
            ZIO.never
        }
      }
  end installAndHang

  /** Child-scope sidecar, interrupted and re-acquired on each stamp change. */
  private def restartSidecar(
      config: PreviewConfig,
      sidecar: ZIO[Scope, Throwable, Any],
  ): ZIO[Scope, Throwable, Nothing] =
    val stamp = config.root.resolve(config.stamp)
    ZIO.scoped {
      sidecar.forkScoped.flatMap { fiber =>
        val failed =
          fiber.await.flatMap {
            case Exit.Success(_)                    => ZIO.never
            case Exit.Failure(c) if c.isInterrupted => ZIO.never
            case Exit.Failure(c)                    => ZIO.failCause(c)
          }
        failed.raceFirst(stampEvents(stamp).take(1).runDrain)
      }
    }.forever
  end restartSidecar

  private[preview] def browseCommand(osName: String, url: String): Seq[String] =
    val os = osName.toLowerCase(java.util.Locale.ROOT)
    if os.contains("mac") then Seq("open", url)
    else if os.contains("win") then Seq("rundll32", "url.dll,FileProtocolHandler", url)
    else Seq("xdg-open", url)

  private def openBrowser(url: String): UIO[Unit] =
    val cmd = browseCommand(sys.props.getOrElse("os.name", ""), url)
    ZIO
      .attemptBlocking {
        val pb = new ProcessBuilder(cmd*)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.start()
        ()
      }
      .tapError(e => Console.printLineError(s"Could not open $url: ${e.getMessage}"))
      .ignore
  end openBrowser

  private[preview] def isUnderRoot(root: File, candidate: File): Boolean =
    val rootPath = root.getCanonicalFile.toPath.normalize
    val candPath = candidate.getCanonicalFile.toPath.normalize
    candPath.startsWith(rootPath)

  private def isReload(requestPath: Path, reload: JPath): Boolean =
    val got  = requestPath.segments
    val want = Chunk.fromIterator(reload.normalize.iterator().asScala.map(_.toString))
    got == want

  private[preview] def stampEvents(stamp: JPath): ZStream[Any, Nothing, ServerSentEvent] =
    ZStream
      .tick(50.millis)
      .mapZIO(_ => readStamp(stamp))
      .zipWithPrevious
      .collect { case (Some(prev), next) if prev != next => ServerSentEvent("reload", Some("reload")) }

  private def readStamp(path: JPath): UIO[Option[String]] =
    ZIO
      .attemptBlocking {
        if Files.isRegularFile(path) then Some(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
        else None
      }
      .orElseSucceed(None)

  private def resolveFile(docRoot: File, path: Path): Option[File] =
    val relative = path.segments.mkString("/")
    if path.segments.exists(s => s == ".." || s.contains("..")) then return None
    val target =
      if relative.isEmpty then docRoot
      else new File(docRoot, relative)
    val canonical =
      try target.getCanonicalFile
      catch case _: Exception => return None
    val rootCanon = docRoot.getCanonicalFile
    if !isUnderRoot(rootCanon, canonical) then None
    else
      val file =
        if canonical.isDirectory then new File(canonical, "index.html")
        else canonical
      Option.when(file.isFile && file.canRead && isUnderRoot(rootCanon, file))(file)
  end resolveFile
end Preview
