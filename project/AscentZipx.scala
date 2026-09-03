import scala.collection.immutable.ListMap

import sbt.AutoPlugin
import sbt.Keys.testFull
import sbt.LocalProject
import sbt.Test
import sbt./
import zipx.plugin.ZipxPlugin
import zipx.plugin.ZipxPlugin.autoImport.*

import chekhov.sbt.ChekhovPlugin.autoImport.chekhovInstall

/** Ascent's zipx CI: platform Verify, e2e, Central, Pages. Lives here so `build.sbt` does not import zipx's `Exec`
  * (which collides with sbt's own `Exec`).
  */
object AscentZipx extends AutoPlugin:
  override def trigger  = allRequirements
  override def requires = ZipxPlugin

  private val javaOpts = Map("JAVA_OPTS" -> EnvValue.plain("-Dfile.encoding=UTF-8"))

  private val TestJs     = CapabilityName("test-js")
  private val TestNative = CapabilityName("test-native")

  /** `addCommandAlias` names (`testJVM` / `testJS` / `testNative`) are not task keys. */
  private def alias(name: String): SbtCommand =
    SbtCommand.raw(name).fold(msg => sys.error(s"zipx: $msg"), identity)

  /** `apt-get update && apt-get install -y <packages>` as a shell AST rather than a string. */
  private def aptInstall(packages: Word*): Script =
    Script(
      Exec("sudo", Word.lit("apt-get"), Word.lit("update")) &&
        Exec.of(
          "sudo",
          List(Word.lit("apt-get"), Word.lit("install"), Word.lit("-y")) ++ packages.toList,
        )
    )

  /** Pins come from generate-time `StepContext.actions` (jar defaults plus catalog `Action` rows). `zipxActions.value`
    * is still Defaults at setting evaluation, so extra catalog pins are not visible there.
    */
  private val jsCiSetup: Steps = Steps.buildingWith("ascent-js-ci") { ctx =>
    List(
      Step
        .usesRef(ctx.actions.setupNode)
        .named("Set up Node")
        .withInputs(ListMap("node-version" -> "24", "cache" -> "npm")),
      Step
        .run(
          aptInstall(
            Word.lit("libcairo2-dev"),
            Word.lit("libpango1.0-dev"),
            Word.lit("libjpeg-dev"),
            Word.lit("libgif-dev"),
            Word.lit("librsvg2-dev"),
          )
        )
        .named("Install canvas build dependencies"),
      Step.run(Script(Exec("npm", Word.lit("ci")))).named("Install Node dependencies (jsdom, canvas)"),
    )
  }

  private val nativeCiSetup: Steps = Steps.built("ascent-native-ci")(
    Step
      .run(
        aptInstall(
          Word.lit("clang"),
          Word.lit("libstdc++-12-dev"),
          Word.lit("libgc-dev"),
          Word.lit("libunwind-dev"),
        )
      )
      .named("Install Scala Native build dependencies")
  )

  // Tag publish restores zipx's LocalDir `target` cache; cleanFull so doc is not incremental against stale TASTy.
  private val publishCleanFull: Steps = Steps.built("publish-cleanFull")(
    Step
      .run(Script(Exec("sbt", Word.squote("cleanFull"))))
      .named("cleanFull")
  )

  override def buildSettings: Seq[sbt.Setting[?]] = Seq(
    zipxJavaVersion      := JdkVersion("25"),
    zipxWorkflowDispatch := true,
    zipxEnv              := Map(
      "PLAYWRIGHT_BROWSERS_PATH" ->
        EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright"))
    ),
    zipxCapabilities ++= Seq(
      Capability.once(
        name = Capability.TestName,
        command = alias("testJVM"),
        env = javaOpts,
      ),
      Capability.once(
        name = TestJs,
        command = alias("testJS"),
        extraSteps = jsCiSetup,
        env = javaOpts,
      ),
      Capability.once(
        name = TestNative,
        command = alias("testNative"),
        extraSteps = nativeCiSetup,
        env = javaOpts,
      ),
      Capability
        .once(
          name = CapabilityName("e2e"),
          command = zipxTasks.session(
            LocalProject("e2e") / chekhovInstall,
            LocalProject("e2e") / Test / testFull,
            LocalProject("ascentChekhovJS") / Test / testFull,
          ),
        )
        .withNodeVersion(NodeVersion("24")),
      ZipxCentral.release.withExtraSteps(ZipxCentral.gpgImportSteps ++ publishCleanFull),
      ZipxDocs.pages(),
    ),
  )
end AscentZipx
