package ascent.preview.sbt

import _root_.sbt.*
import _root_.sbt.Keys.*
import _root_.sbt.nio.Keys.watchOnTermination
import sjsonnew.BasicJsonProtocol.given
import AscentPreviewPort.given

/** Local static preview: serve a directory once, watch a rebuild task, never restart Preview.
  *
  * The loop is `sbt ~<module>/ascentPreview`. sbt 2 `~` walks the compiled graph of `ascentPreviewRebuild` (default JS:
  * `ascentPreviewStage` → `ascentPreviewBundle` → `spliceFast`). Do not set `watchTriggers` on these keys: a non-empty
  * set replaces transitive `fileInputs`.
  *
  * `ascentPreviewServe` is idempotent, so the Preview JVM stays up; rebuilds rewrite `assets/dev-stamp` and the tab
  * reloads over SSE.
  *
  * Enable on the module you want to type (`todoConduitJS`, `docs`). Specular docs should set `ascentPreviewRebuild` to
  * `specularSite` / `specularSiteDev` and `ascentPreviewRoot` to the site directory. Scala.js apps keep the default
  * rebuild (`ascentPreviewStage`).
  *
  * Do not name this package `sbt` (shadows `_root_.sbt`).
  */
object AscentPreviewPlugin extends AutoPlugin:

  override def requires: Plugins      = plugins.JvmPlugin
  override def trigger: PluginTrigger = noTrigger

  object autoImport:
    type AscentPreviewPort = ascent.preview.sbt.AscentPreviewPort
    val AscentPreviewPort   = ascent.preview.sbt.AscentPreviewPort
    val ascentPreviewEnable =
      settingKey[Boolean](
        "When false, skip forking Preview and printing the URL (rebuild still runs so ~ has a watch graph)"
      )
    val ascentPreviewAutoServe =
      settingKey[Boolean](
        "When true, ascentPreview starts ascentPreviewMain. False when a JVM app already calls Preview.serve"
      )
    val ascentPreviewMain =
      settingKey[String](
        "Fully-qualified main class forked by ascentPreviewServe (default ascent.preview.PreviewMain)"
      )
    val ascentPreviewAutoOpen =
      settingKey[Boolean](
        "When true, open the preview URL in a browser once the preview process binds (not on ~ rebuilds)"
      )
    val ascentPreviewRoot =
      settingKey[File]("Directory Preview serves (JS default: <sources' parent>/target/preview)")
    val ascentPreviewPort =
      settingKey[AscentPreviewPort](
        """Preview bind: AscentPreviewPort(8765) or AscentPreviewPort("auto") (first free >= 8700)."""
      )
    val ascentPreviewIndex =
      settingKey[File]("index.html copied by ascentPreviewStage")
    val ascentPreviewLibVersion =
      settingKey[String](
        "If non-empty, add rocks.earlyeffect:ascent-preview_3 at this version to Compile (for PreviewMain)"
      )
    val ascentPreviewBundle =
      taskKey[File]("JS file to stage (default: this project's spliceFast, if defined)")
    val ascentPreviewClasspath =
      taskKey[Classpath]("JVM classpath that contains ascentPreviewMain (default ascent.preview.PreviewMain)")
    val ascentPreviewStage =
      taskKey[File]("Link/copy JS, copy index.html, write assets/dev-stamp into ascentPreviewRoot")
    val ascentPreviewRebuild =
      taskKey[Unit]("Update the served tree and stamp; this is what ~ascentPreview re-runs")
    val ascentPreviewServe =
      taskKey[Unit]("Start ascentPreviewMain in the background if it is not already running for this project")
    val ascentPreview =
      taskKey[Unit]("Rebuild, then start Preview once. Watch with sbt ~<module>/ascentPreview")
  end autoImport

  import autoImport.*

  private val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  private val spliceFastKey: TaskKey[File] = TaskKey[File]("spliceFast")

  override def projectSettings: Seq[Setting[?]] = Seq(
    ascentPreviewEnable     := true,
    ascentPreviewAutoServe  := true,
    ascentPreviewMain       := "ascent.preview.PreviewMain",
    ascentPreviewAutoOpen   := false,
    ascentPreviewPort       := AscentPreviewPort(8765),
    ascentPreviewLibVersion := "",
    ascentPreviewRoot       := Def.uncached(sourceDirectory.value.getParentFile / "target" / "preview"),
    ascentPreviewIndex      := Def.uncached(sourceDirectory.value.getParentFile / "index.html"),
    libraryDependencies ++= {
      val v = ascentPreviewLibVersion.value
      if v.isEmpty then Nil
      else Seq("rocks.earlyeffect" % "ascent-preview_3" % v)
    },
    ascentPreviewClasspath := Def.uncached((Compile / fullClasspath).value),
    ascentPreviewBundle    := Def.uncached {
      spliceFastKey.?.value.getOrElse {
        sys.error(
          "ascentPreviewBundle is not set and spliceFast is not defined on this project. " +
            "Set ascentPreviewBundle (spliceFast or fastLinkJS output), or override ascentPreviewRebuild."
        )
      }
    },
    ascentPreviewStage   := Def.uncached(stageTree.value),
    ascentPreviewRebuild := Def.uncached {
      val _ = ascentPreviewStage.value
      ()
    },
    ascentPreviewServe := Def.uncached(ensureTreeThenServe.value),
    // Rebuild is a static .value so sbt 2 ~ sees its fileInputs. Serve runs in this body afterward:
    // `{ rebuild.value; serve.value }` would fork in parallel with an empty root.
    ascentPreview := Def.uncached {
      val enabled = ascentPreviewEnable.value
      val auto    = ascentPreviewAutoServe.value
      val log     = streams.value.log
      val base    = baseDirectory.value
      val _       = ascentPreviewRebuild.value
      if enabled && auto then
        startPreviewIfNeeded(
          service = bgJobService.value,
          log = log,
          converter = fileConverter.value,
          st = state.value,
          rs = Keys.resolvedScoped.value,
          root = ascentPreviewRoot.value,
          requested = ascentPreviewPort.value,
          cp = ascentPreviewClasspath.value,
          autoOpen = ascentPreviewAutoOpen.value,
          main = ascentPreviewMain.value,
          base = base,
        )
        logPreviewUrl(log, readBoundPort(base))
      end if
    },
    ascentPreview / aggregate          := false,
    ascentPreviewServe / aggregate     := false,
    ascentPreviewStage / aggregate     := false,
    ascentPreviewRebuild / aggregate   := false,
    ascentPreview / watchOnTermination := {
      val termScope = Keys.resolvedScoped.value.scope
      (_, _, _, state) =>
        val service = Project.extract(state).get(bgJobService)
        stopPreviewJobs(service, termScope)
        state
    },
  )

  /** Rebuild automatically when the served tree is missing (`docs/ascentPreviewServe` alone, first clone, …). */
  private def ensureTreeThenServe: Def.Initialize[Task[Unit]] = Def.taskDyn {
    val root = ascentPreviewRoot.value
    if root.isDirectory then serveIfNeeded
    else
      Def.taskDyn {
        streams.value.log.info(s"ascentPreviewServe: $root missing, running ascentPreviewRebuild")
        ascentPreviewRebuild.value
        serveIfNeeded
      }
  }

  private def stageTree: Def.Initialize[Task[File]] = Def.task {
    val dest   = ascentPreviewRoot.value
    val index  = ascentPreviewIndex.value
    val bundle = ascentPreviewBundle.value
    IO.createDirectory(dest)
    IO.createDirectory(dest / "assets")
    val jsDest = dest / "fast.js"
    if bundle.getCanonicalFile != jsDest.getCanonicalFile then IO.copyFile(bundle, jsDest)
    if !index.exists then sys.error(s"ascentPreviewStage: index.html missing at $index")
    IO.copyFile(index, dest / "index.html")
    IO.write(dest / "assets" / "dev-stamp", System.currentTimeMillis.toString)
    dest
  }

  private def serveIfNeeded: Def.Initialize[Task[Unit]] = Def.task {
    startPreviewIfNeeded(
      service = bgJobService.value,
      log = streams.value.log,
      converter = fileConverter.value,
      st = state.value,
      rs = Keys.resolvedScoped.value,
      root = ascentPreviewRoot.value,
      requested = ascentPreviewPort.value,
      cp = ascentPreviewClasspath.value,
      autoOpen = ascentPreviewAutoOpen.value,
      main = ascentPreviewMain.value,
      base = baseDirectory.value,
    )
  }

  private def startPreviewIfNeeded(
      service: BackgroundJobService,
      log: Logger,
      converter: xsbti.FileConverter,
      st: State,
      rs: Def.ScopedKey[?],
      root: File,
      requested: AscentPreviewPort,
      cp: Classpath,
      autoOpen: Boolean,
      main: String,
      base: File,
  ): Unit =
    val already = service.jobs.exists(job => isPreviewJob(job.spawningTask, rs.scope))
    if already then log.info(s"ascentPreviewServe: already running ${root.getAbsolutePath}")
    else
      if !root.exists then sys.error(s"ascentPreviewServe: root does not exist: $root (run ascentPreviewRebuild first)")
      val port = AscentPreviewPort.resolve(requested)
      writeBoundPort(base, port)
      val jars =
        cp.map(af => converter.toPath(af.data).toFile.getAbsolutePath)
          .mkString(java.io.File.pathSeparator)
      val args = Seq(
        "-cp",
        jars,
        main,
        port.toString,
        root.getAbsolutePath,
      ) ++ (if autoOpen then Seq("--open") else Nil)
      log.info(s"ascentPreviewServe: serving ${root.getAbsolutePath}")
      service.runInBackground(rs, st) { (logger, workingDir) =>
        val opts = ForkOptions()
          .withOutputStrategy(Some(LoggedOutput(logger)))
          .withRunJVMOptions(jdk24PlusRunOptions.toVector)
          .withWorkingDirectory(workingDir)
        val code = Fork.java(opts, args)
        if code != 0 then sys.error(s"$main exited $code")
      }
      ()
    end if
  end startPreviewIfNeeded

  /** OSC 8 hyperlink so Cursor / VS Code / iTerm can Cmd-click the URL. */
  private def logPreviewUrl(log: Logger, port: Int): Unit =
    val url = s"http://localhost:$port/"
    log.info(url)
    val esc  = "\u001b"
    val link = s"${esc}]8;;${url}${esc}\\${esc}[4m${url}${esc}[0m${esc}]8;;${esc}\\"
    System.out.println(link)
    System.out.flush()

  private def portStateFile(base: File): File =
    base / "target" / "ascent-preview.port"

  private def writeBoundPort(base: File, port: Int): Unit =
    IO.createDirectory(base / "target")
    IO.write(portStateFile(base), port.toString)

  private def readBoundPort(base: File): Int =
    val f = portStateFile(base)
    if !f.isFile then sys.error(s"ascentPreview: missing ${f.getAbsolutePath} (Preview was not started)")
    IO.read(f).trim.toInt

  private def isPreviewSpawn(label: String): Boolean =
    label == ascentPreviewServe.key.label || label == ascentPreview.key.label

  private def isPreviewJob(spawning: ScopedKey[?], scope: Scope): Boolean =
    isPreviewSpawn(spawning.key.label) &&
      spawning.scope.project == scope.project

  private def stopPreviewJobs(service: BackgroundJobService, scope: Scope): Unit =
    service.jobs
      .filter { h =>
        isPreviewSpawn(h.spawningTask.key.label) && h.spawningTask.scope.project == scope.project
      }
      .foreach { h =>
        service.stop(h)
        service.waitForTry(h)
        ()
      }
  end stopPreviewJobs
end AscentPreviewPlugin
