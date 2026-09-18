import sbt.ScopeAxis.{Select, Zero}
import sbt.nio.Keys.fileInputs

scalaVersion := "3.9.0"

enablePlugins(AscentPreviewPlugin)

ascentPreviewAutoServe := false

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

lazy val scheduleHelloRewrite =
  taskKey[Unit]("Rewrite Hello.scala after a delay so the watch loop can observe it")

lazy val checkWatchFired =
  taskKey[Unit]("Fail unless the watch cycle restaged after-watch title and a new stamp")

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

scheduleHelloRewrite := Def.uncached {
  val src = (Compile / scalaSource).value / "Hello.scala"
  val t   = new Thread("scheduleHelloRewrite"):
    override def run(): Unit =
      Thread.sleep(4000)
      IO.write(src, "object Hello:\n  def greeting: String = \"after-watch\"\n")
  t.setDaemon(true)
  t.start()
  ()
}

checkWatchFired := Def.uncached {
  val dest  = ascentPreviewRoot.value
  val html  = IO.read(dest / "index.html")
  val stamp = IO.read(dest / "assets" / "dev-stamp")
  val once  = IO.read(baseDirectory.value / "target" / "once-stamp")
  if !html.contains("after-watch") then
    sys.error(s"staged index title was not after-watch:\n$html")
  if stamp.isEmpty then sys.error("empty assets/dev-stamp")
  if stamp == once then sys.error(s"dev-stamp did not change after watch rebuild: $stamp")
  ()
}

lazy val recordOnceStamp =
  taskKey[Unit]("Remember the one-shot stamp so checkWatchFired can see a change")

recordOnceStamp := Def.uncached {
  val stamp = IO.read(ascentPreviewRoot.value / "assets" / "dev-stamp")
  IO.createDirectory(baseDirectory.value / "target")
  IO.write(baseDirectory.value / "target" / "once-stamp", stamp)
  ()
}

checkWatchGraph := Def.uncached {
  val extracted = Project.extract(state.value)
  import extracted.given
  def detailsOf(key: AttributeKey[?]): String =
    val sk = Def.ScopedKey(Scope(Select(extracted.currentRef), Zero, Zero, Zero), key)
    Project.details(extracted.structure, false, sk)
  val preview = detailsOf(ascentPreview.key)
  val rebuild = detailsOf(ascentPreviewRebuild.key)
  val globs   = (ascentPreview / fileInputs).value
  val hello   = ((Compile / scalaSource).value / "Hello.scala").toPath
  val index   = ascentPreviewIndex.value.toPath
  if !globs.exists(_.matches(hello)) then
    sys.error(s"ascentPreview / fileInputs ($globs) does not watch $hello")
  if !globs.exists(_.matches(index)) then
    sys.error(s"ascentPreview / fileInputs ($globs) does not watch $index")
  if !preview.contains("fileInputs") then
    sys.error(s"ascentPreview inspect missing fileInputs:\n$preview")
  if !rebuild.contains("compile") then
    sys.error(s"ascentPreviewRebuild inspect missing compile:\n$rebuild")
  ()
}
