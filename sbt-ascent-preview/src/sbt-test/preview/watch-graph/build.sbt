import sbt.ScopeAxis.{Select, Zero}
import sbt.nio.Keys.fileInputs

scalaVersion := "3.9.0"

enablePlugins(AscentPreviewPlugin)

ascentPreviewAutoServe := false
ascentPreviewRebuild   := Def.uncached {
  val _ = (Compile / compile).value
  ()
}

lazy val checkWatchGraph =
  taskKey[Unit]("Fail unless ascentPreview fileInputs matches Hello.scala")

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
  if !globs.exists(_.matches(hello)) then
    sys.error(s"ascentPreview / fileInputs ($globs) does not watch $hello")
  if !preview.contains("fileInputs") then
    sys.error(s"ascentPreview inspect missing fileInputs:\n$preview")
  if !preview.contains("ascentPreviewRebuild") then
    sys.error(s"ascentPreview inspect missing ascentPreviewRebuild:\n$preview")
  if !rebuild.contains("compile") then
    sys.error(s"ascentPreviewRebuild inspect missing compile:\n$rebuild")
  ()
}
