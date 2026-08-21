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
  * [[routes]] is the testable surface (no sockets). [[serveForever]] is the process-lifetime wrapper used by
  * [[PreviewMain]] and sbt-ascent-preview. SSE is checked before the trailing static handler so `/__ascent/reload` is
  * never mistaken for a file.
  */
object Preview:

  /** Build routes for `config` without starting a server. */
  def routes(config: PreviewConfig): Routes[Any, Response] =
    val docRoot = config.root.toAbsolutePath.normalize.toFile
    val base    = Routes(
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
    if config.cors then base @@ Middleware.cors else base
  end routes

  /** Block forever serving `config.root` on `config.port`. Opens the URL after bind when [[PreviewConfig.openBrowser]].
    */
  def serveForever(config: PreviewConfig): UIO[Nothing] =
    val dir = config.root.toAbsolutePath.normalize.toFile
    Server
      .install(routes(config))
      .flatMap { bound =>
        val url = s"http://localhost:$bound/"
        Console.printLine(s"Serving ${dir.getAbsolutePath}") *>
          Console.printLine(s"Open $url") *>
          ZIO.when(config.openBrowser)(openBrowser(url)) *>
          ZIO.never
      }
      .provide(Server.defaultWith(_.port(config.port)))
      .orDie
  end serveForever

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
