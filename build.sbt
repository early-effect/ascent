import ascent.preview.sbt.AscentPreviewPlugin
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import ascent.preview.sbt.AscentPreviewPort
import chekhov.ChekhovBrowser
import chekhov.jsenv.ChekhovJSEnv

MyVersions.settings
AscentZipx.settings

ThisBuild / scalaVersion := (MyVersions.scala: String)

val scala3Version: String = MyVersions.scala

organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(uri("https://www.earlyeffect.rocks"))
versionScheme        := Some("early-semver")
homepage             := Some(uri("https://github.com/early-effect/ascent"))
licenses             := Seq("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo              := Some(
  ScmInfo(
    uri("https://github.com/early-effect/ascent"),
    "scm:git@github.com:early-effect/ascent.git",
  )
)
developers := List(
  Developer(
    "russwyte",
    "Russ White",
    "356303+russwyte@users.noreply.github.com",
    uri("https://github.com/russwyte"),
  )
)

publishMavenStyle    := true
pomIncludeRepository := { _ => false }
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

val scalaVersions = Seq(scala3Version)

Global / concurrentRestrictions ++= Seq(
  Tags.limit(NativeTags.Link, 1),
  Tags.limit(Tags.Compile, 4),
)

val javaTimePolyfill  = MyVersions.javaTime
val nativeSerialTests = Seq(Test / parallelExecution := false)
val nativeJavaTime    = MyVersions.javaTime ++ nativeSerialTests

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

val zioTestSettings = MyVersions.zioTests

val jsdomTestEnv = Def.settings(
  Test / jsEnv := Def.uncached(new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv())
)

/** JS examples: stage into `<example>/target/preview` and fork the local `preview` module's PreviewMain. */
def examplePreviewSettings(autoServe: Boolean): Seq[Setting[?]] = Seq(
  spliceFastOutput       := Def.uncached(ascentPreviewRoot.value / "fast.js"),
  ascentPreviewAutoServe := autoServe,
  ascentPreviewClasspath := Def.uncached((LocalProject("preview") / Compile / fullClasspath).value),
)


lazy val root = (project in file("."))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .aggregate(
    (domTypes.projectRefs ++ core.projectRefs ++ domFacade.projectRefs ++ domCore.projectRefs ++
      mountEngine.projectRefs ++ js.projectRefs ++ element.projectRefs ++ mcpApp.projectRefs ++ mcpHost.projectRefs ++
      domgen.projectRefs ++ css.projectRefs ++ conduitBridge.projectRefs ++ history.projectRefs ++
      html.projectRefs ++ datastar.projectRefs ++ datastarJs.projectRefs ++
      datastarHttp.projectRefs ++ datastarExample.projectRefs ++ datastarExampleServer.projectRefs ++
      hybridChat.projectRefs ++ hybridChatServer.projectRefs ++
      todoConduit.projectRefs ++ mcpHostDemo.projectRefs ++ mcpHostDemoView.projectRefs ++ docs.projectRefs ++
      preview.projectRefs ++
      ascentChekhov.projectRefs :+
      LocalProject("chekhovJs") :+
      LocalProject("sbtAscentPreview")) *
  )
  .settings(
    name := "ascent",
    // sonaRelease reads this project's version and refuses a -SNAPSHOT. Root is never published.
    version        := "",
    publish / skip := true,
    test / skip    := true,
  )

// --- ascent-dom-types : generated element/attr/event defs + codecs (zero deps, jvm/js/native) ---
lazy val domTypes = (projectMatrix in file("dom-types"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "ascent-dom-types",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions)
  .nativePlatform(scalaVersions = scalaVersions, nativeSerialTests)

// --- ascent-domgen : pure-Scala generator, JVM tooling only (never a runtime dep) ---
lazy val domgen = (projectMatrix in file("domgen"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name           := "ascent-domgen",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    MyVersions.domgenLib,
    libraryDependencies += MyVersions.moduleID(MyVersions.scalafmtDynamic).cross(CrossVersion.for3Use2_13),
    zioTestSettings,
    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent-core : Squawk + AST + DSL. ZIO-based; depends on dom-types + zio; jvm/js/native ---
lazy val core = (projectMatrix in file("core"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(domTypes)
  .settings(
    name := "ascent-core",
    scalacOptions ++= commonScalacOptions,
    MyVersions.zioLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  // ZIO references java.time on its JS and Native targets - polyfill it via scala-java-time.
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)
  .nativePlatform(scalaVersions = scalaVersions, nativeJavaTime)

// --- ascent-dom-facade : our @js.native DOM facade (js only, no scalajs-dom) ---
lazy val domFacade = (projectMatrix in file("dom-facade"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(domTypes)
  .settings(
    name := "ascent-dom-facade",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

// --- ascent-dom-core : platform-neutral structural DOM catalog (Node/Element/Document/EventTarget/
lazy val domCore = (projectMatrix in file("dom-core"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core, css)
  .settings(
    name := "ascent-dom-core",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) => p.dependsOn(domFacade.js(scala3Version)).settings(jsdomTestEnv),
  )
  .nativePlatform(scalaVersions = scalaVersions, nativeSerialTests)

// --- ascent-mount-engine : the cross-platform Mount/Slot/Cleanup binding engine ---
lazy val mountEngine = (projectMatrix in file("mount-engine"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core, domCore, css)
  .settings(
    name := "ascent-mount-engine",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, jsdomTestEnv)
  .nativePlatform(scalaVersions = scalaVersions, nativeSerialTests)

// --- ascent-js : DOM mount/binding engine + typed event DSL + DomStyleSink (js only) ---
lazy val js = (projectMatrix in file("js"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core, domFacade, css, mountEngine)
  .settings(
    name := "ascent-js",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

lazy val element = (projectMatrix in file("element"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(js)
  .settings(
    name := "ascent-element",
    scalacOptions ++= commonScalacOptions,
    MyVersions.elementLib,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

lazy val mcpApp = (projectMatrix in file("mcp-app"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(js)
  .settings(
    name := "ascent-mcp-app",
    scalacOptions ++= commonScalacOptions,
    MyVersions.mcpAppLib,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

lazy val mcpHost = (projectMatrix in file("mcp-host"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(element)
  .settings(
    name := "ascent-mcp-host",
    scalacOptions ++= commonScalacOptions,
    MyVersions.mcpHostLib,
    zioTestSettings,
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.settings(
        scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
        Test / jsEnv := Def.uncached(
          ChekhovJSEnv(browser = ChekhovBrowser.Firefox, headless = true, keepOpen = false)
        ),
      ),
  )

lazy val history = (projectMatrix in file("history"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core)
  .settings(
    name := "ascent-history",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) => p.dependsOn(domFacade.js(scala3Version)).settings(jsdomTestEnv),
  )
  .nativePlatform(scalaVersions = scalaVersions, nativeSerialTests)

lazy val conduitBridge = (projectMatrix in file("conduit"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core)
  .settings(
    name := "ascent-conduit",
    scalacOptions ++= commonScalacOptions,
    MyVersions.conduitLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)
  .nativePlatform(scalaVersions = scalaVersions, nativeJavaTime)

lazy val css = (projectMatrix in file("css"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core)
  .settings(
    name := "ascent-css",
    scalacOptions ++= commonScalacOptions,
    MyVersions.cssLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, jsdomTestEnv)
  .nativePlatform(scalaVersions = scalaVersions, nativeSerialTests)

lazy val html = (projectMatrix in file("html"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core, css, mountEngine)
  .settings(
    name := "ascent-html",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)
  .nativePlatform(scalaVersions = scalaVersions, nativeJavaTime)

lazy val datastar = (projectMatrix in file("datastar"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core)
  .settings(
    name := "ascent-datastar",
    scalacOptions ++= commonScalacOptions,
    MyVersions.datastarLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)
  .jsPlatform(scalaVersions = scalaVersions, javaTimePolyfill)
  .nativePlatform(scalaVersions = scalaVersions, nativeJavaTime)

lazy val datastarJs = (projectMatrix in file("datastar-js"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastar, js, domFacade)
  .settings(
    name := "ascent-datastar-js",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

lazy val datastarHttp = (projectMatrix in file("datastar-http"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(html, datastar)
  .settings(
    name := "ascent-datastar-http",
    scalacOptions ++= commonScalacOptions,
    MyVersions.datastarHttpLib,
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

lazy val preview = (projectMatrix in file("preview"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "ascent-preview",
    scalacOptions ++= commonScalacOptions,
    MyVersions.previewLib,
    zioTestSettings,
    Compile / mainClass := Some("ascent.preview.PreviewMain"),
    run / mainClass     := Some("ascent.preview.PreviewMain"),
  )
  .jvmPlatform(scalaVersions = scalaVersions)

lazy val sbtAscentPreview = (project in file("sbt-ascent-preview"))
  .enablePlugins(SbtPlugin)
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "sbt-ascent-preview",
    scalacOptions ++= commonScalacOptions,
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewPlugin.scala",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewPort.scala",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewWatch.scala",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewCommand.scala",
    MyVersions.sbtPreviewLib,
    scriptedLaunchOpts ++= Seq("-Xmx2g", s"-Dplugin.version=${version.value}"),
    scriptedBufferLog := false,
  )

// --- ascent example: todo-conduit — TodoMVC over conduit (js only) ---
lazy val todoConduit = (projectMatrix in file("example/todo-conduit"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(js, css, conduitBridge, history)
  .settings(
    name           := "ascent-todo-conduit",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) => p.enablePlugins(AscentPreviewPlugin).settings(examplePreviewSettings(autoServe = true)),
  )

// --- ascent example: mcp-host — a host page that frames MCP App views in <ascent-mcp-view> (js only) ---
val mcpHostDemoShared = Def.setting(
  (ThisBuild / baseDirectory).value / "example" / "mcp-host" / "shared" / "src" / "main" / "scala"
)

lazy val mcpHostDemoView = (projectMatrix in file("example/mcp-host/view"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(mcpApp)
  .settings(
    name           := "ascent-mcp-host-demo-view",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    Compile / unmanagedSourceDirectories += mcpHostDemoShared.value,
  )
  .jsPlatform(scalaVersions)

lazy val mcpHostDemo = (projectMatrix in file("example/mcp-host/host"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(mcpHost)
  .settings(
    name           := "ascent-mcp-host-demo",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / unmanagedSourceDirectories += mcpHostDemoShared.value,
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) => {
      val view = LocalProject("mcpHostDemoViewJS")
      p.enablePlugins(AscentPreviewPlugin)
        .settings(
          examplePreviewSettings(autoServe = true),
          ascentPreviewStage := Def.uncached {
            val staged = ascentPreviewStage.value
            val _      = (view / Compile / fastLinkJS).value
            IO.copyFile(
              (view / Compile / fastLinkJSOutput).value / "main.js",
              ascentPreviewRoot.value / "counter-view.js",
            )
            staged
          },
          ascentPreview / fileInputs ++= (view / Compile / unmanagedSources / fileInputs).value,
          ascentPreviewRebuild / fileInputs ++= (view / Compile / unmanagedSources / fileInputs).value,
        )
    },
  )

// --- ascent example: datastar-app — server-driven counter proving the full datastar loop ---
lazy val datastarExample = (projectMatrix in file("example/datastar-app"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastarJs, css)
  .settings(
    name           := "ascent-datastar-example",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.enablePlugins(AscentPreviewPlugin)
        .settings(
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
        )
        .settings(examplePreviewSettings(autoServe = false)),
  )

// --- ascent example: datastar-app SERVER — the heddle backend (JVM). Holds the count, serves the
//   datastar SSE stream + the increment action via the ascent-datastar-http wrapper, with heddle-brotli
//   compression, and composes ascent-preview so the spliced client is same-origin. ---
lazy val datastarExampleServer = (projectMatrix in file("example/datastar-app-server"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastarHttp, preview)
  .settings(
    name           := "ascent-datastar-example-server",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    MyVersions.brotli,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

lazy val hybridChat = (projectMatrix in file("example/hybrid-chat"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastarJs, css)
  .settings(
    name           := "ascent-hybrid-chat",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.enablePlugins(AscentPreviewPlugin)
        .settings(
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
        )
        .settings(examplePreviewSettings(autoServe = false)),
  )

lazy val hybridChatServer = (projectMatrix in file("example/hybrid-chat-server"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastarHttp, preview)
  .settings(
    name           := "ascent-hybrid-chat-server",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    MyVersions.brotli,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent-docs : Specular DocSpecs + static site (JVM) and interactive client (JS) ---
lazy val docs: ProjectMatrix = (projectMatrix in file("docs"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core, css, conduitBridge, html, datastar, history)
  .settings(
    name           := "ascent-docs",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    description := "Effect-native reactive UI for Scala 3; docs site",
    // Each module releases on its own number, so install snippets name theirs from the Ship rows. Both rows compile
    // the pages, so both generate it.
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "ascent" / "docs" / "Released.scala"
      IO.write(out, ReleasedGen.source(zipxShips.value))
      Seq(out)
    }.taskValue,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(datastarHttp.jvm(scala3Version), preview.jvm(scala3Version))
        .enablePlugins(SpecularPlugin, AscentPreviewPlugin)
        .settings(
          MyVersions.docsJvm,
          zioTestSettings,
          Compile / mainClass             := Some("ascent.docs.ServeSite"),
          run / mainClass                 := Some("ascent.docs.ServeSite"),
          Compile / discoveredMainClasses := Seq("ascent.docs.ServeSite"),
          Test / mainClass                := Some("ascent.docs.BuildSite"),
          Test / discoveredMainClasses    := Seq("ascent.docs.BuildSite"),
          specularBuildMain               := "ascent.docs.BuildSite",
          specularMetaProject             := Some(LocalProject("root")),
          specularSiteDirectory           := (ThisBuild / baseDirectory).value / "target" / "site",
          ascentPreviewRoot               := specularSiteDirectory.value,
          ascentPreviewAutoOpen           := true,
          ascentPreviewPort               := AscentPreviewPort.auto,
          ascentPreviewRebuild            := Def.uncached(specularSiteDev.value),
          // Link the JS client and write a marker path BuildSite copies into assets/client.js.
          specularJsLink := Def.uncached {
            (LocalProject("docsJS") / Compile / fastLinkJS).value
            val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
            val mainJs = outDir / "main.js"
            if (!mainJs.exists) {
              sys.error(
                s"Expected $mainJs after fastLinkJS; directory contains: " +
                  Option(outDir.list).toSeq.flatten.mkString(", ")
              )
            }
            val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
            IO.write(marker, mainJs.getAbsolutePath)
            ()
          },
          specularJsLinkDev := Def.uncached(specularJsLink.value),
        ),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(js.js(scala3Version))
        .settings(
          javaTimePolyfill,
          MyVersions.docsJs,
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
          Compile / mainClass := Some("ascent.docs.ClientMain"),
        ),
  )

// Platform-scoped test aliases for zipx CI (sbt 2: `test` == testQuick; prefer that over `testFull`).
lazy val ascentMatrices: Seq[ProjectMatrix] = Seq(
  domTypes,
  core,
  domFacade,
  domCore,
  mountEngine,
  js,
  element,
  mcpApp,
  domgen,
  css,
  conduitBridge,
  history,
  html,
  datastar,
  datastarJs,
  datastarHttp,
  preview,
  datastarExample,
  datastarExampleServer,
  hybridChat,
  hybridChatServer,
  todoConduit,
  docs,
)

def ascentPlatformTestCommand(find: ProjectMatrix => ProjectFinder): String =
  ascentMatrices
    .flatMap(m => find(m).get.map(p => s"${p.id}/test"))
    .distinct
    .sorted
    .mkString("; ")

// ascentChekhov is not in ascentMatrices: the JVM row is browser-free selector tests and
// joins testJVM. Live JSEnv tests live in chekhovJs (ChekhovJSEnv, not jsdom) so they stay
// off testJS.
addCommandAlias(
  "testJVM",
  ascentPlatformTestCommand(_.jvm) + "; ascentChekhov/test; sbtAscentPreview/scripted",
)
addCommandAlias("testJS", ascentPlatformTestCommand(_.js))
addCommandAlias("testNative", ascentPlatformTestCommand(_.native))

lazy val e2eStage = taskKey[Unit]("Stage example preview trees for Chekhov e2e")

lazy val ascentChekhov = (projectMatrix in file("chekhov"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(core)
  .settings(
    name := "ascent-chekhov",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) => p.settings(MyVersions.chekhovCoreLib),
  )
  .jsPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(js.js(scala3Version))
        .settings(
          MyVersions.chekhovDomLib,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
        ),
  )

lazy val chekhovJs = (project in file("chekhov-js"))
  .enablePlugins(org.scalajs.sbtplugin.ScalaJSPlugin)
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(js.js(scala3Version), ascentChekhov.js(scala3Version))
  .settings(
    name           := "ascent-chekhov-js",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    // ChekhovPlugin sets Test / fork := true (JVM). JSEnv is constructed directly.
    Test / jsEnv := Def.uncached(
      ChekhovJSEnv(browser = ChekhovBrowser.Firefox, headless = true, keepOpen = false)
    ),
  )

lazy val e2e = (project in file("e2e"))
  .dependsOn(
    preview.jvm(scala3Version),
    datastarExampleServer.jvm(scala3Version),
    hybridChatServer.jvm(scala3Version),
    ascentChekhov.jvm(scala3Version),
  )
  .settings(
    name           := "ascent-e2e",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    MyVersions.e2eTests,
    chekhovBrowsers := Seq(chekhov.ChekhovBrowser.Firefox),
    e2eStage        := Def.uncached {
      Def
        .sequential(
          LocalProject("todoConduitJS") / ascentPreviewStage,
          LocalProject("datastarExampleJS") / ascentPreviewStage,
          LocalProject("hybridChatJS") / ascentPreviewStage,
        )
        .value
      ()
    },
    Test / javaOptions += s"-Dascent.repoRoot=${(ThisBuild / baseDirectory).value.getAbsolutePath}",
    // CompileAnalysis has no JsonFormat; sbt 2 caches task outputs unless we opt out.
    Test / compile := Def.uncached((Test / compile).dependsOn(e2eStage).value),
  )
