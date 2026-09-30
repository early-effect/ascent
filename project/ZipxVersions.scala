import sbt.{Def, Setting}
import sbt.Keys.{dependencyOverrides, libraryDependencySchemes}
import sbt.librarymanagement.syntax.*
import zipx.*

/** Typed catalog: every library and plugin this build may use, and the version each published module ships at.
  * `zipxDepUpdate` rewrites `Lib` / `Plugin` constructors; `zipxModverBump <row> [kind]` rewrites a `Ship` /
  * `ShipGroup`. A row holds its next release: every build is `<row>-SNAPSHOT` until `zipx-release.yml` releases it.
  *
  * sbt-zipx is not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`). sbt-pgp is not a row: zipx
  * already brings it in. Action pins stay on jar defaults.
  *
  * Parent `Lib` vals used only for `.mod` are catalog rows; they are not `library()`-selected when another selected
  * module already pulls them (specular-core / specular-site via the docs theme).
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.1.0-M3")
  val scala: ScalaVersion = ScalaVersion("3.9.0")

  val zio                = Lib("dev.zio", "zio", "2.1.26")
  val zioTest            = zio.mod("zio-test")
  val zioTestSbt         = zio.mod("zio-test-sbt")
  val zioStreams         = zio.mod("zio-streams")
  val zioJson      = Lib("dev.zio", "zio-json", "1.1.0")
  val heddle       = Lib("rocks.earlyeffect", "heddle", "0.8.0-SNAPSHOT")
  val heddleBrotli = heddle.mod("heddle-brotli")
  val heddleApps   = heddle.mod("heddle-mcp-apps")
  val heddleFrame  = heddle.mod("heddle-mcp-apps-frame")

  val scalaJavaTime     = Lib("io.github.cquiroz", "scala-java-time", "2.7.0")
  val scalaJavaTimeTzdb = scalaJavaTime.mod("scala-java-time-tzdb")

  val fastparse = Lib("com.lihaoyi", "fastparse", "3.1.1")
  val conduit = Lib("rocks.earlyeffect", "conduit", "0.0.7")

  val scalafmtDynamic = Lib("org.scalameta", "scalafmt-dynamic", "3.11.5")
    .excluding(ZipxExclude.org("org.scala-lang.modules", "scala-collection-compat_2.13"))

  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.18.1")
  val specularZioTest = specular.mod("specular-zio-test")
  val specularTheme   = specular.mod("early-effect-docs-theme")

  val chekhovZioTest = Lib("rocks.earlyeffect", "chekhov-zio-test", "0.1.2")
  val chekhovDriver  = chekhovZioTest.mod("chekhov-driver")
  val chekhovCore    = chekhovZioTest.mod("chekhov-core")
  val chekhovDom     = chekhovZioTest.mod("chekhov-dom")
  val neotype        = Lib("io.github.kitlangton", "neotype", "0.7.2")

  val scalajs        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalaNative    = Plugin("org.scala-native", "sbt-scala-native", "0.5.12")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val scalafix       = Plugin("ch.epfl.scala", "sbt-scalafix", "0.14.9")
  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.18.1")
  val sbtSplice      = Plugin("rocks.earlyeffect", "sbt-splice", "0.3.1")
  val sbtReload      = Plugin("com.jamesward", "sbt-reload", "0.0.8")
  val sbtChekhov     = Plugin("rocks.earlyeffect", "sbt-chekhov", "0.1.2")

  // --- What ascent ships. One row per module a consumer can take alone; a group only where modules cannot move apart.
  //   Every row started at 0.9.0, ascent's last lockstep release. A JS-only module has no bare project, so its row
  //   names the JS one.

  /** One generator and one webref snapshot produce all three, so they move together. */
  val dom = ShipGroup("dom", "0.10.0")("domTypes", "domFacadeJS", "domCore")

  val core          = Ship("core", "0.9.0")
  val css           = Ship("css", "0.9.0")
  val mountEngine   = Ship("mountEngine", "0.10.0")
  val js            = Ship("jsJS", "0.10.0")
  val element       = Ship("elementJS", "0.9.0")
  val mcpApp        = Ship("mcpAppJS", "0.9.0")
  val mcpHost       = Ship("mcpHostJS", "0.9.0")
  val history       = Ship("history", "0.9.0")
  val conduitBridge = Ship("conduitBridge", "0.9.0")
  val html          = Ship("html", "0.9.0")
  val datastar      = Ship("datastar", "0.9.0")
  val datastarJs    = Ship("datastarJsJS", "0.9.0")
  val datastarHttp  = Ship("datastarHttp", "0.9.0")
  val ascentChekhov = Ship("ascentChekhov", "0.10.0")

  /** sbt-ascent-preview adds ascent-preview at its own version, so the two ship together. */
  val preview = ShipGroup("preview", "0.9.0")("preview", "sbtAscentPreview")

  def zioTests = library(zioTest.test, zioTestSbt.test)
  def zioLib   = library(zio)
  def javaTime = library(scalaJavaTime, scalaJavaTimeTzdb)

  // Workaround until the next ZIO release: zio-test-sbt 2.1.26 still pins 0.5.10, which swallows
  // Native test output. Alias the sbt-scala-native version onto the Native Maven coordinate.
  def nativeTestInterface: Seq[Setting[?]] =
    val testInterface = "org.scala-native" % "test-interface_native0.5_3" % (scalaNative.version: String)
    Seq(
      libraryDependencySchemes += "org.scala-native" % "test-interface_native0.5_3" % "early-semver",
      dependencyOverrides += Def.uncached(testInterface),
    )
  def nativeJavaTime  = javaTime ++ nativeTestInterface
  def cssLib          = library(fastparse)
  def elementLib      = library(zioStreams)
  def conduitLib      = library(conduit)
  def datastarLib     = library(zioJson)
  def datastarHttpLib = library(heddle)
  def previewLib      = library(heddle)
  def mcpAppLib       = library(heddleApps)
  def mcpHostLib      = library(heddleFrame)
  def brotli          = library(heddleBrotli)
  def domgenLib       = library(zioJson, fastparse)
  def docsJvm         = library(specularZioTest, specularTheme)
  def docsJs          = library(specular, zioTest)
  def e2eTests        = library(chekhovZioTest.test, chekhovDriver.test)
  def chekhovCoreLib  = library(chekhovCore)
  def chekhovDomLib   = library(chekhovDom)
  def sbtPreviewLib   = library(neotype)
end MyVersions
