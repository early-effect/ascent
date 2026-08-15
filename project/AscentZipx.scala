import scala.collection.immutable.ListMap

import sbt.AutoPlugin
import zipx.sbt.ZipxPlugin
import zipx.sbt.ZipxPlugin.autoImport.*

/** Ascent's zipx CI: platform Verify, e2e, Central, Pages. Lives here so `build.sbt` does not import zipx's `Exec`
  * (which collides with sbt's own `Exec`).
  */
object AscentZipx extends AutoPlugin:
  override def trigger  = allRequirements
  override def requires = ZipxPlugin

  private val javaOpts = Map("JAVA_OPTS" -> EnvValue.plain("-Dfile.encoding=UTF-8"))

  private val Fmt        = CapabilityName("fmt")
  private val TestJs     = CapabilityName("test-js")
  private val TestNative = CapabilityName("test-native")

  /** `apt-get update && apt-get install -y <packages>` as a shell AST rather than a string. */
  private def aptInstall(packages: Word*): Script =
    Script(
      Exec("sudo", Word.lit("apt-get"), Word.lit("update")) &&
        Exec.of(
          "sudo",
          List(Word.lit("apt-get"), Word.lit("install"), Word.lit("-y")) ++ packages.toList,
        )
    )

  private val jsCiSetup: Steps = Steps.built("ascent-js-ci")(
    Step
      .uses("actions/setup-node@820762786026740c76f36085b0efc47a31fe5020") // v7.0.0
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

  private val dependencySubmission: Steps = Steps.built("ascent-dependency-submission")(
    Step
      .uses("scalacenter/sbt-dependency-submission@d84eef4c09e633bcf5f113bcad7fd5e9af1baee9") // v3.2.3
      .named("Submit dependency graph")
  )

  override def buildSettings: Seq[sbt.Setting[?]] = Seq(
    zipxJavaVersion      := JdkVersion("25"),
    zipxTestTask         := "test",
    zipxWorkflowDispatch := true,
    zipxScalaSteward     := true,
    zipxEnv              := Map(
      "PLAYWRIGHT_BROWSERS_PATH" ->
        EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright"))
    ),
    zipxCapabilities ++= Seq(
      Capability.once(Fmt, SbtCommand("scalafmtCheckAll; zipxWorkflowCheck")),
      Capability.once(
        name = Capability.TestName,
        command = SbtCommand("testJVM"),
        needsCapabilities = List(Fmt),
        env = javaOpts,
      ),
      Capability.once(
        name = TestJs,
        command = SbtCommand("testJS"),
        needsCapabilities = List(Fmt),
        extraSteps = jsCiSetup,
        env = javaOpts,
      ),
      Capability.once(
        name = TestNative,
        command = SbtCommand("testNative"),
        needsCapabilities = List(Fmt),
        extraSteps = nativeCiSetup,
        env = javaOpts,
      ),
      Capability
        .once(
          name = CapabilityName("e2e"),
          command = SbtCommand("e2e/chekhovInstall; e2e/testFull"),
          needsCapabilities = List(Fmt),
        )
        .withNodeVersion(NodeVersion("24")),
      ZipxCentral.release,
      ZipxDocs.pages(),
      Capability.once(
        name = CapabilityName("dependency-submission"),
        // zipx Once jobs always emit an sbt step. Run the action in extraSteps *before* that step: an earlier
        // `sbt about` would start a server without GITHUB_TOKEN, and the action's later sbt client would reuse it
        // (snapshot generates, submit then fails with "Missing environment variable GITHUB_TOKEN").
        command = SbtCommand("about"),
        needsCapabilities = List(Capability.TestName, TestJs, TestNative),
        permissions = Map("contents" -> "write"),
        extraSteps = dependencySubmission,
      ),
    ),
  )
end AscentZipx
