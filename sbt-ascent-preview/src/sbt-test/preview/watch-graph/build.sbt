import sbt.ScopeAxis.{Select, Zero}

scalaVersion := "3.9.0"

enablePlugins(AscentPreviewPlugin)

ascentPreviewAutoServe := false
ascentPreviewRebuild   := Def.uncached {
  val _ = (Compile / compile).value
  ()
}

lazy val checkWatchGraph =
  taskKey[Unit]("Fail unless inspect ascentPreview lists rebuild and compile")

checkWatchGraph := Def.uncached {
  val extracted = Project.extract(state.value)
  import extracted.given
  def detailsOf(key: AttributeKey[?]): String =
    val sk = Def.ScopedKey(Scope(Select(extracted.currentRef), Zero, Zero, Zero), key)
    Project.details(extracted.structure, false, sk)
  val preview = detailsOf(ascentPreview.key)
  val rebuild = detailsOf(ascentPreviewRebuild.key)
  if !preview.contains("ascentPreviewRebuild") then
    sys.error(s"ascentPreview inspect missing ascentPreviewRebuild:\n$preview")
  if !rebuild.contains("compile") then
    sys.error(s"ascentPreviewRebuild inspect missing compile:\n$rebuild")
  ()
}
