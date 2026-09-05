package ascent.preview

import zio.*
import zio.http.*
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
    * Sidecar and HTTP run beside each other (`zipPar`) in the caller's `Scope`. A sidecar that returns still keeps its
    * finalizers registered until that scope closes; a never-ending sidecar is interrupted with the server. Sidecar
    * failure interrupts HTTP.
    *
    * Extra routes are installed as `extraRoutes ++` static/reload so concrete paths (`/sse`, `/__beard/events`) win
    * over the static `GET / trailing` handler. CORS, when enabled, wraps the combined app.
    *
    * If extra routes close over a resource, acquire it in this same `Scope` **before** calling [[serve]] so bind cannot
    * race the resource. Do not also pass that resource as `sidecar` (zipPar would race).
    *
    * `restartSidecarOnStamp` (default false) reruns the sidecar in a child scope each time stamp bytes change. HTTP
    * stays up; extra routes are not reinstalled. Use it for a worker you want reset on `~` rebuild, not for a resource
    * the installed routes close over.
    *
    * Provide `Server` at the call site (`PreviewMain` uses `Server.defaultWith(_.port(config.port))`; examples add
    * compression). [[Server.install]] returns the bound port for logging and [[PreviewConfig.openBrowser]].
    */
  def serve(
      config: PreviewConfig,
      sidecar: ZIO[Scope, Throwable, Any] = ZIO.unit,
      extraRoutes: Routes[Any, Response] = Routes.empty,
      restartSidecarOnStamp: Boolean = false,
  ): ZIO[Scope & Server, Throwable, Nothing] =
    val app  = withCors(config, extraRoutes ++ staticAndReload(config))
    val side =
      if restartSidecarOnStamp then restartSidecar(config, sidecar)
      else sidecar
    side.zipParRight(installAndHang(config, app))
  end serve

  private def staticAndReload(config: PreviewConfig): Routes[Any, Response] =
    val docRoot = config.root.toAbsolutePath.normalize.toFile
    Routes(
      Method.GET / trailing ->
        Handler
          .identity[Request]
          .flatMap { request =>
            if isReload(request.path, config.reloadPath) then sseHandler(config)
            else
              resolveFile(docRoot, request.path) match
                case Some(file) => Handler.fromFile(file)
                case None       => Handler.notFound
          }
          .catchAll {
            case _: java.io.FileNotFoundException       => Handler.notFound
            case _: java.nio.file.AccessDeniedException => Handler.status(Status.Forbidden)
            case _                                      => Handler.notFound
          }
    )
  end staticAndReload

  private def withCors(config: PreviewConfig, app: Routes[Any, Response]): Routes[Any, Response] =
    if config.cors then app @@ Middleware.cors else app

  private def installAndHang(config: PreviewConfig, app: Routes[Any, Response]): ZIO[Server, Throwable, Nothing] =
    val dir = config.root.toAbsolutePath.normalize.toFile
    Server.install(app).flatMap { bound =>
      val url = s"http://localhost:$bound/"
      Console.printLine(s"Serving ${dir.getAbsolutePath}") *>
        Console.printLine(s"Open $url") *>
        ZIO.when(config.openBrowser)(openBrowser(url)) *>
        ZIO.never
    }

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

  /** `open` on macOS, `xdg-open` elsewhere, `rundll32` on Windows. Not executed in tests; command only. */
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

  /** True when `candidate` is `root` or a descendant (canonical paths, not string prefix). */
  private[preview] def isUnderRoot(root: File, candidate: File): Boolean =
    val rootPath = root.getCanonicalFile.toPath.normalize
    val candPath = candidate.getCanonicalFile.toPath.normalize
    candPath.startsWith(rootPath)

  private def isReload(requestPath: Path, reload: JPath): Boolean =
    val got  = requestPath.dropLeadingSlash.encode
    val want = reload.normalize.iterator().asScala.map(_.toString).mkString("/")
    got == want

  private def sseHandler(config: PreviewConfig): Handler[Any, Nothing, Request, Response] =
    handler { (_: Request) =>
      Response.fromServerSentEvents(stampEvents(config.root.resolve(config.stamp)))
    }

  /** Skip the snapshot at subscribe; emit one `reload` event when stamp bytes change. */
  private[preview] def stampEvents(stamp: JPath): ZStream[Any, Nothing, ServerSentEvent[String]] =
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
    val relative = path.dropLeadingSlash.encode
    if relative.contains("..") then return None
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
