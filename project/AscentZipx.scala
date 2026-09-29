import scala.collection.immutable.ListMap

import sbt.*
import sbt.Keys.testFull
import zipx.plugin.ZipxPlugin.autoImport.*
import zipx.shell.Exec as ZipxExec

import chekhov.sbt.ChekhovPlugin.autoImport.chekhovInstall

/** Ascent's zipx CI: platform Verify, e2e, per-module Central releases, Pages. */
object AscentZipx:

  private val javaOpts = Map("JAVA_OPTS" -> EnvValue.plain("-Dfile.encoding=UTF-8"))

  private val TestJs     = CapabilityName("test-js")
  private val TestNative = CapabilityName("test-native")

  /** `addCommandAlias` names (`testJVM` / `testJS` / `testNative`) are not task keys. */
  private def alias(name: String): SbtCommand =
    SbtCommand.raw(name).fold(msg => sys.error(s"zipx: $msg"), identity)

  /** sbt 2 packages class directories as `classes.sbtdir.zip` for the action cache. Concurrent compiles of the same
    * module can drop that zip (`coreJS / compileIncremental`: file referenced by the build does not exist). Limit
    * Compile to 1 in the e2e session only: a global limit of 1 serializes test / test-js / test-native as well.
    */
  private val serializeE2eCompile: SbtCommand =
    SbtCommand
      .raw("set Global / concurrentRestrictions += Tags.limit(Tags.Compile, 1)")
      .fold(msg => sys.error(s"zipx: $msg"), identity)

  /** `apt-get update && apt-get install -y <packages>` as a shell AST rather than a string. */
  private def aptInstall(packages: Word*): Script =
    Script(
      ZipxExec("sudo", Word.lit("apt-get"), Word.lit("update")) &&
        ZipxExec.of(
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
      Step.run(Script(ZipxExec("npm", Word.lit("ci")))).named("Install Node dependencies (jsdom, canvas)"),
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

  // A publish restores zipx's LocalDir `target` cache; cleanFull so doc is not incremental against stale TASTy.
  private val publishCleanFull: Steps = Steps.built("publish-cleanFull")(
    Step
      .run(Script(ZipxExec("sbt", Word.squote("cleanFull"))))
      .named("cleanFull")
  )

  /** Each module whose `Ship` row moved on a push to main publishes signed in its own job and stages its tree;
    * `ZipxCentral.releaseOnce` then merges every staged tree and releases them to Maven Central once.
    */
  private val publishMoved: Capability =
    ZipxModver
      .publish()
      .withEnv(ZipxCentral.signingEnv)
      .withExtraSteps(ZipxCentral.gpgImportSteps ++ publishCleanFull)
      // The plugin facade does not re-export this one; the core pack it wraps is on the build classpath.
      .withPostSteps(zipx.central.ZipxCentral.uploadStagingSteps)

  private val releaseMoved: Capability = ZipxCentral.releaseOnce.copy(gate = Gate.OnDefaultPush)

  /** No tags under `Ship` rows, so the site follows main: every push to it, and a manual dispatch. */
  private val docsFollowMain: Capability = ZipxDocs.pages().copy(condition = Some(JobCondition.onDefaultPush(List("main"))))

  def settings: Seq[Setting[?]] = Seq(
    zipxJavaVersion      := JdkVersion("25"),
    zipxWorkflowDispatch := true,
    zipxEnv              := Map(
      "PLAYWRIGHT_BROWSERS_PATH" ->
        EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright"))
    ),
    zipxCapabilities ++= Seq(
      // Replaces the builtin test by name, so it has to claim the LocalDir snapshot itself.
      Capability
        .once(
          name = Capability.TestName,
          command = alias("testJVM"),
          env = javaOpts,
        )
        .withLocalCache(LocalCacheMode.Save),
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
            serializeE2eCompile,
            LocalProject("e2e") / chekhovInstall,
            LocalProject("e2e") / Test / testFull,
            LocalProject("chekhovJs") / Test / testFull,
          ),
        )
        .withNodeVersion(NodeVersion("24")),
      publishMoved,
      releaseMoved,
      docsFollowMain,
    ),
    zipxCacheEpoch := CacheEpoch.ShipCatalog,
  )
end AscentZipx
