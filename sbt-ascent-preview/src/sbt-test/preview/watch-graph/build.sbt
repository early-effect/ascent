import sbt.ScopeAxis.{Select, Zero}
import sbt.nio.Keys.fileInputs

scalaVersion := "3.9.0"

enablePlugins(AscentPreviewPlugin)

ascentPreviewAutoServe := false

libraryDependencies += "org.scalameta" %% "munit" % "1.1.1" % Test

def helloGreeting(src: File): String =
  val Greeting = """def greeting: String = "([^"]+)"""".r
  Greeting.findFirstMatchIn(IO.read(src)).map(_.group(1)).getOrElse("missing")

ascentPreviewRebuild := Def.uncached {
  val _     = (Compile / compile).value
  val dest  = ascentPreviewRoot.value
  val hello = (Compile / scalaSource).value / "Hello.scala"
  val title = helloGreeting(hello)
  IO.createDirectory(dest)
  IO.createDirectory(dest / "assets")
  IO.write(
    dest / "index.html",
    s"<!doctype html><html><head><title>$title</title></head><body><h1>$title</h1></body></html>\n",
  )
  IO.write(dest / "assets" / "dev-stamp", System.currentTimeMillis.toString)
  ()
}

lazy val checkWatchGraph =
  taskKey[Unit]("Fail unless ascentPreview fileInputs matches Hello.scala and index.html")

lazy val proveNestedRunTask =
  taskKey[Unit]("Nested Extracted.runTask(rebuild) twice after a source edit")

lazy val rewriteHello =
  taskKey[Unit]("Rewrite Hello.scala so the background watch can observe it")

lazy val checkWatchFired =
  taskKey[Unit]("Fail unless the watch cycle restaged after-watch title and a new stamp")

lazy val recordOnceStamp =
  taskKey[Unit]("Remember the one-shot stamp so waitWatchFired can see a change")

lazy val forceCompile =
  taskKey[Unit]("Wipe class products and zinc analysis, then compile Compile and Test")

proveNestedRunTask := Def.uncached {
  val st0       = state.value
  val extracted = Project.extract(st0)
  val (st1, _)  = extracted.runTask(ascentPreviewRebuild, st0)
  val src       = (Compile / scalaSource).value / "Hello.scala"
  IO.write(src, "object Hello:\n  def greeting: String = \"second\"\n")
  val (st2, _) = Project.extract(st1).runTask(ascentPreviewRebuild, st1)
  val _        = st2
  ()
}

rewriteHello := Def.uncached {
  val src = (Compile / scalaSource).value / "Hello.scala"
  IO.write(src, "object Hello:\n  def greeting: String = \"after-watch\"\n")
}

checkWatchFired := Def.uncached {
  val dest  = ascentPreviewRoot.value
  val html  = IO.read(dest / "index.html")
  val stamp = IO.read(dest / "assets" / "dev-stamp")
  val once  = IO.read(baseDirectory.value / "once-stamp")
  if !html.contains("after-watch") then
    sys.error(s"staged index title was not after-watch:\n$html")
  if stamp.isEmpty then sys.error("empty assets/dev-stamp")
  if stamp == once then sys.error(s"dev-stamp did not change after watch rebuild: $stamp")
  ()
}

recordOnceStamp := Def.uncached {
  val stamp = IO.read(ascentPreviewRoot.value / "assets" / "dev-stamp")
  IO.write(baseDirectory.value / "once-stamp", stamp)
}

forceCompile := Def.taskDyn {
  val classes     = (Compile / classDirectory).value
  val testClasses = (Test / classDirectory).value
  val tgt         = target.value
  IO.delete(classes)
  IO.delete(testClasses)
  def wipeZinc(dir: File): Unit =
    if dir.isDirectory then
      val fs = dir.listFiles
      if fs != null then
        fs.foreach { f =>
          if f.isDirectory then wipeZinc(f)
          else if f.getName.contains("inc_compile") then IO.delete(f)
        }
  wipeZinc(tgt)
  Def.task {
    val _ = (Compile / compile).value
    val _ = (Test / compile).value
    ()
  }
}

checkWatchGraph := Def.uncached {
  val extracted = Project.extract(state.value)
  import extracted.given
  def detailsOf(key: AttributeKey[?]): String =
    val sk = Def.ScopedKey(Scope(Select(extracted.currentRef), Zero, Zero, Zero), key)
    Project.details(extracted.structure, false, sk)
  val watch   = detailsOf(ascentPreviewWatch.key)
  val rebuild = detailsOf(ascentPreviewRebuild.key)
  val globs   = (ascentPreview / fileInputs).value
  val hello   = ((Compile / scalaSource).value / "Hello.scala").toPath
  val index   = ascentPreviewIndex.value.toPath
  if !globs.exists(_.matches(hello)) then
    sys.error(s"ascentPreview / fileInputs ($globs) does not watch $hello")
  if !globs.exists(_.matches(index)) then
    sys.error(s"ascentPreview / fileInputs ($globs) does not watch $index")
  if !watch.contains("fileInputs") then
    sys.error(s"ascentPreviewWatch inspect missing fileInputs:\n$watch")
  if !rebuild.contains("compile") then
    sys.error(s"ascentPreviewRebuild inspect missing compile:\n$rebuild")
  ()
}
