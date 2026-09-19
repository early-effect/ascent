package ascent.preview.sbt

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import _root_.sbt.*
import _root_.sbt.Keys.*
import _root_.sbt.nio.Keys.{fileInputs, watchOnTermination}
import _root_.sbt.nio.file.Glob
import sjsonnew.BasicJsonProtocol.given
import AscentPreviewPort.given

/** Local static preview: serve a directory once, watch sources, never restart Preview.
  *
  * `sbt <module>/ascentPreview` from a terminal stays in the foreground until interrupt (Ctrl-C). Typed at an sbt
  * prompt, it returns so tests and compiles still run; stop with `ascentPreviewStop`. The poller is a
  * `BackgroundJobService` job (not sbt `~`, not a Command on the queue). It watches
  * `Compile / unmanagedSources / fileInputs` plus `ascentPreviewIndex` with a private `FileTreeView`. On change it
  * appends `ascentPreviewWatchFire` to the live session so rebuilds serialize with `test` / `compile`. zinc `compile`
  * does not depend on `unmanagedSources`; do not set `watchTriggers` (a non-empty set replaces transitive
  * `fileInputs`). Do not watch `ascentPreviewRoot` (stamp/JS output would loop).
  *
  * `ascentPreviewServe` is idempotent, so the Preview JVM stays up; rebuilds rewrite `assets/dev-stamp` and the tab
  * reloads over SSE (`location.reload()`). That is the refresh: Ascent has no VDOM.
  *
  * Enable on the module you want to type (`todoConduitJS`, `docs`). Specular docs should set `ascentPreviewRebuild` to
  * `specularSite` / `specularSiteDev` and `ascentPreviewRoot` to the site directory. Scala.js apps keep the default
  * rebuild (`ascentPreviewStage`). `ascentPreviewOnce` is the one-shot (rebuild + serve, return, no watch).
  * `ascentPreviewStop` stops this project's watch and Preview JVM.
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
        "When false, skip forking Preview and printing the URL (rebuild and the watch still run)"
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
        "When true, open the preview URL in a browser once the preview process binds (not on watch rebuilds)"
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
      taskKey[Unit]("Update the served tree and stamp; re-run on each watch cycle")
    val ascentPreviewServe =
      taskKey[Unit]("Start ascentPreviewMain in the background if it is not already running for this project")
    val ascentPreviewOnce =
      taskKey[Unit]("Rebuild and start Preview once, then return (no watch)")
    val ascentPreviewWatch =
      taskKey[Unit]("Start the background source poller if it is not already running for this project")
    val ascentPreviewStop =
      taskKey[Unit]("Stop this project's preview watch and Preview JVM")
    val ascentPreview =
      taskKey[StateTransform](
        "Rebuild, start Preview, and watch sources. Foreground until interrupt when it is the last command; otherwise return to the prompt"
      )
  end autoImport

  import autoImport.*

  private val jdk24PlusRunOptions: Seq[String] = Seq(
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
  )

  private val spliceFastKey: TaskKey[File] = TaskKey[File]("spliceFast")

  /** Runs rebuild then clears the coalesce flag. Watch submits this, not rebuild, so user overrides of
    * `ascentPreviewRebuild` still clear the flag.
    */
  private val ascentPreviewWatchFire: TaskKey[Unit] = TaskKey[Unit]("ascentPreviewWatchFire")

  private val rebuildQueued: ConcurrentHashMap[String, AtomicBoolean] = ConcurrentHashMap()

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
    ascentPreviewStage := Def.uncached(stageTree.value),
    // Poller reads these globs. zinc compile never lists unmanagedSources; index.html is not a
    // Scala source. Do not watch ascentPreviewRoot (stamp/JS output would loop).
    ascentPreview / fileInputs ++= (Compile / unmanagedSources / fileInputs).value,
    ascentPreview / fileInputs ++= Seq(Glob(ascentPreviewIndex.value)),
    ascentPreviewRebuild / fileInputs ++= (Compile / unmanagedSources / fileInputs).value,
    ascentPreviewRebuild / fileInputs ++= Seq(Glob(ascentPreviewIndex.value)),
    ascentPreviewRebuild := Def.uncached {
      val _ = (ascentPreviewRebuild / fileInputs).value
      val _ = ascentPreviewStage.value
      ()
    },
    ascentPreviewWatchFire := Def.uncached {
      val id = projectId(Keys.resolvedScoped.value.scope)
      try ascentPreviewRebuild.value
      finally queuedFlag(id).set(false)
    },
    ascentPreviewServe := Def.uncached(ensureTreeThenServe.value),
    ascentPreviewOnce  := Def.uncached(previewOnce.value),
    ascentPreviewWatch := Def.uncached(startWatchIfNeeded.value),
    ascentPreviewStop  := Def.uncached {
      val service = bgJobService.value
      val scope   = Keys.resolvedScoped.value.scope
      streams.value.log.info("ascentPreview: stopped")
      stopPreviewJobs(service, scope)
    },
    // First rebuild+serve is a normal task evaluation so Execute can finish. The watch is a
    // background job; it must not call runTask (nested Execute deadlocks sbt 2 on a Scala.js graph).
    // It appends ascentPreviewWatchFire to the session instead. Last command (`sbt <module>/ascentPreview`):
    // waitFor the watch job so Ctrl-C stops Preview. Otherwise return to the prompt.
    ascentPreview := Def.uncached {
      val log     = streams.value.log
      val st      = state.value
      val service = bgJobService.value
      val scope   = Keys.resolvedScoped.value.scope
      val _       = previewOnce.value
      val _       = ascentPreviewWatch.value
      if shouldAwait(st) then
        log.info("ascentPreview: watching; interrupt to stop")
        awaitWatch(service, scope, log)
        StateTransform(dropKeepAlive)
      else
        log.info("ascentPreview: watching in the background (ascentPreviewStop to stop)")
        StateTransform(identity)
    },
    ascentPreview / aggregate          := false,
    ascentPreviewOnce / aggregate      := false,
    ascentPreviewServe / aggregate     := false,
    ascentPreviewWatch / aggregate     := false,
    ascentPreviewStop / aggregate      := false,
    ascentPreviewStage / aggregate     := false,
    ascentPreviewRebuild / aggregate   := false,
    ascentPreviewWatchFire / aggregate := false,
    ascentPreview / watchOnTermination := {
      val termScope = Keys.resolvedScoped.value.scope
      (_, _, _, state) =>
        val service = Project.extract(state).get(bgJobService)
        stopPreviewJobs(service, termScope)
        state
    },
  )

  private def shouldAwait(s: State): Boolean =
    s.remainingCommands.forall(e => isKeepAlive(e.commandLine))

  private def isKeepAlive(commandLine: String): Boolean =
    val c = commandLine.trim
    c.isEmpty ||
    c == BasicCommandStrings.Shell ||
    c == "exit" ||
    c.startsWith(BasicCommandStrings.IfLast)

  private def dropKeepAlive(s: State): State =
    s.copy(remainingCommands = s.remainingCommands.filterNot(e => isKeepAlive(e.commandLine)))

  /** Block until interrupt, the watch job dies, or `ascentPreviewStop`. Polls so Ctrl-C (which cancels the task engine
    * and auto-cancel jobs) can land; `waitFor` on a latch would not.
    */
  private def awaitWatch(service: BackgroundJobService, scope: Scope, log: Logger): Unit =
    service.jobs.filter(h => isWatchJob(h.spawningTask, scope)).foreach(enableAutoCancel)
    try
      while service.jobs.exists(h => isWatchJob(h.spawningTask, scope)) &&
        !Thread.currentThread.isInterrupted
      do
        try Thread.sleep(200)
        catch case _: InterruptedException => return
    finally
      stopPreviewJobs(service, scope)
      log.info("ascentPreview: stopped")
  end awaitWatch

  /** sbt Cancel calls `DefaultBackgroundJobService.stop()`, which only interrupts auto-cancel jobs. */
  private def enableAutoCancel(job: JobHandle): Unit =
    try
      val f = job.getClass.getDeclaredField("isAutoCancel")
      f.setAccessible(true)
      f.setBoolean(job, true)
    catch case NonFatal(_) => ()
  end enableAutoCancel

  private def previewOnce: Def.Initialize[Task[Unit]] = Def.task {
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
  }

  private def startWatchIfNeeded: Def.Initialize[Task[Unit]] = Def.task {
    val service = bgJobService.value
    val rs      = Keys.resolvedScoped.value
    val st      = state.value
    val log     = streams.value.log
    val globs   = (ascentPreview / fileInputs).value
    val scope   = rs.scope
    val already = service.jobs.exists(job => isWatchJob(job.spawningTask, scope))
    if already then log.info(s"ascentPreviewWatch: already running")
    else
      val cmd        = fireCommand(scope)
      val scopedFire = scope.copy(task = Zero) / ascentPreviewWatchFire
      val id         = projectId(scope)
      val flag       = queuedFlag(id)
      service.runInBackground(rs, st) { (logger, _) =>
        watchUntilStopped(globs, cmd, scopedFire, flag, logger)
      }
      ()
    end if
  }

  private def watchUntilStopped(
      globs: Seq[Glob],
      cmd: String,
      scopedFire: TaskKey[Unit],
      flag: AtomicBoolean,
      logger: Logger,
  ): Unit =
    var snap       = AscentPreviewWatch.snapshot(globs)
    var queuedSnap = Option.empty[AscentPreviewWatch.Snapshot]
    while !Thread.currentThread.isInterrupted do
      if queuedSnap.isDefined && !flag.get then
        val now = AscentPreviewWatch.snapshot(globs)
        if now != queuedSnap.get then
          queueRebuild(globs, cmd, scopedFire, flag, logger) match
            case Some(queued) => queuedSnap = Some(queued)
            case None         => queuedSnap = None
        else
          snap = now
          queuedSnap = None
      else
        AscentPreviewWatch.await(globs, snap, listenStdin = false) match
          case AscentPreviewWatch.Event.Stop    => return
          case AscentPreviewWatch.Event.Changed =>
            queueRebuild(globs, cmd, scopedFire, flag, logger) match
              case Some(queued) => queuedSnap = Some(queued)
              case None         => ()
    end while
  end watchUntilStopped

  /** Interactive sessions: append to CommandExchange so rebuilds serialize with the prompt. Scripted IPC never drains
    * that queue between `>` lines (`-Dsbt.scripted=true`); run the task from the watch thread against the live State
    * instead.
    */
  private def queueRebuild(
      globs: Seq[Glob],
      cmd: String,
      scopedFire: TaskKey[Unit],
      flag: AtomicBoolean,
      logger: Logger,
  ): Option[AscentPreviewWatch.Snapshot] =
    if !flag.compareAndSet(false, true) then None
    else
      logger.info(s"ascentPreview: source change, rebuilding ($cmd)")
      val ok =
        if java.lang.Boolean.getBoolean("sbt.scripted") then runFire(scopedFire, logger)
        else AscentPreviewCommand.submit(cmd)
      if ok then Some(AscentPreviewWatch.snapshot(globs))
      else
        flag.set(false)
        logger.error(s"ascentPreview: failed to queue $cmd")
        None
  end queueRebuild

  private def runFire(scopedFire: TaskKey[Unit], logger: Logger): Boolean =
    try
      AscentPreviewCommand.withCurrentState { s =>
        if s == null then
          logger.error("ascentPreview: no session state yet")
          false
        else
          val _ = Project.extract(s).runTask(scopedFire, s)
          true
      }
    catch
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        false
      case e: Exception =>
        logger.error(s"ascentPreview: rebuild failed: ${e.getMessage}")
        false
  end runFire

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
    val already = service.jobs.exists(job => isServeJob(job.spawningTask, rs.scope))
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

  private def isServeSpawn(label: String): Boolean =
    label == ascentPreviewServe.key.label ||
      label == ascentPreview.key.label ||
      label == ascentPreviewOnce.key.label

  private def isWatchSpawn(label: String): Boolean =
    label == ascentPreviewWatch.key.label

  private def sameProject(spawning: ScopedKey[?], scope: Scope): Boolean =
    spawning.scope.project == scope.project

  private def isServeJob(spawning: ScopedKey[?], scope: Scope): Boolean =
    isServeSpawn(spawning.key.label) && sameProject(spawning, scope)

  private def isWatchJob(spawning: ScopedKey[?], scope: Scope): Boolean =
    isWatchSpawn(spawning.key.label) && sameProject(spawning, scope)

  private def stopPreviewJobs(service: BackgroundJobService, scope: Scope): Unit =
    service.jobs
      .filter { h =>
        val label = h.spawningTask.key.label
        (isServeSpawn(label) || isWatchSpawn(label)) && sameProject(h.spawningTask, scope)
      }
      .foreach { h =>
        service.stop(h)
        service.waitForTry(h)
        ()
      }
  end stopPreviewJobs

  private def projectId(scope: Scope): String =
    scope.project match
      case Select(ProjectRef(_, id)) => id
      case Select(LocalProject(id))  => id
      case _                         => "_"

  private def fireCommand(scope: Scope): String =
    val id    = projectId(scope)
    val label = ascentPreviewWatchFire.key.label
    if id == "_" then label else s"$id / $label"

  private def queuedFlag(id: String): AtomicBoolean =
    rebuildQueued.computeIfAbsent(
      id,
      new java.util.function.Function[String, AtomicBoolean]:
        def apply(k: String): AtomicBoolean = new AtomicBoolean(false),
    )
end AscentPreviewPlugin
