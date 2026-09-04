import ascent.preview.sbt.AscentPreviewPlugin
import ascent.preview.sbt.AscentPreviewPlugin.autoImport.*
import ascent.preview.sbt.AscentPreviewPort
import chekhov.ChekhovBrowser
import chekhov.jsenv.ChekhovJSEnv

MyVersions.settings

ThisBuild / scalaVersion := (MyVersions.scala: String)

val scala3Version: String = MyVersions.scala

// sbt 2.x scopes bare build.sbt settings to ThisBuild, so these apply build-wide to every module.
organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(url("https://www.earlyeffect.rocks"))
versionScheme        := Some("early-semver")
// No hardcoded version — sbt-dynver-ci: clean tag -> 0.1.0, else <last-tag>-ci (cache-stable).

homepage := Some(url("https://github.com/early-effect/ascent"))
licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo  := Some(
  ScmInfo(
    url("https://github.com/early-effect/ascent"),
    "scm:git@github.com:early-effect/ascent.git",
  )
)
developers := List(
  Developer(
    "russwyte",
    "Russ White",
    "356303+russwyte@users.noreply.github.com",
    url("https://github.com/russwyte"),
  )
)

// GitHub Actions CI is project/AscentZipx.scala (zipx capabilities).

// Publishing targets the Sonatype Central Portal, which is built into sbt 2.x (no sbt-sonatype).
// Snapshots go to Central's snapshot repo; releases stage locally and are promoted by `sonaRelease`.
publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle    := true
pomIncludeRepository := { _ => false }

// CI-only publishing: the signing key hex comes from the PGP_KEY_HEX env var (a shared early-effect
// org secret), so the key can be rotated in one place. There is no real key in this file — the
// MISSING_KEY_HEX sentinel keeps the build loadable for local compile/test but makes signing fail
// loudly if anyone tries to publish off-CI.
usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

// sbt 2.x defaults eviction to a strict scheme. The Native toolchain (sbt-scala-native 0.5.12)
// forces test-interface 0.5.12, while zio-test-sbt's Native build still pins 0.5.10 — both are
// 0.5.x and binary-compatible, so tell sbt to judge scala-native's libs by early-semver.
libraryDependencySchemes +=
  "org.scala-native" % "test-interface_native0.5_3" % "early-semver"

// zio-schema-json 1.8.5 still pins zio-json 0.9.1 while we resolve 0.10.0. Under early-semver a
// 0.9 -> 0.10 bump reads as breaking, so sbt 2.x's strict eviction check fails the build. The codec
// API in play is unchanged across the bump, so force 0.10.0 rather than hold zio-json back.
libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"

val scalaVersions = Seq(scala3Version)

// Cap peak memory during a full cross-build. The Scala Native link phase (LLVM optimize/codegen) is
// by far the heaviest task — across the ~10 native modules projectMatrix would otherwise run several
// at once, each holding a large heap. Serialize them (the plugin tags `nativeLink` with
// NativeTags.Link). Also cap total parallel compiles so JVM+JS+Native fan-out doesn't pile up.
Global / concurrentRestrictions ++= Seq(
  Tags.limit(NativeTags.Link, 1),
  Tags.limit(Tags.Compile, 4),
)

// java.time polyfills for Scala.js / Native (ZIO uses java.time.Instant under the hood; the JVM
// provides it natively, but the JS and Native targets need scala-java-time + tzdb to link). In
// sbt 2.x plain `%%` appends the project's platform suffix automatically (e.g. `_sjs1`, the role
// the old `%%%` operator played), so this works uniformly across all three platforms.
val javaTimePolyfill = MyVersions.javaTime

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

// zio-test deps, shared by every module. `library()` resolves `%%` at each module's platform.
// The ZTestFramework registers itself automatically via zio-test-sbt, so no testFrameworks wiring.
val zioTestSettings = MyVersions.zioTests

// jsdom-backed test environment for JS modules that need a real DOM (dom-facade engine
// facade tests, ascent-js mount/binding tests). The dependency itself comes from
// project/plugins.sbt; we only need to wire up the jsEnv here. Requires `npm install jsdom`
// in the project root before tests run.
// `JSEnv` has no JsonFormat, and sbt 2.x caches setting values by default — opt this one out of
// caching (it's a fresh, non-serializable env instance) rather than invent a bogus codec.
val jsdomTestEnv = Def.settings(
  Test / jsEnv := Def.uncached(new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv())
)

/** JS examples: stage into `<example>/target/preview` and fork the local `preview` module's PreviewMain. */
def examplePreviewSettings(autoServe: Boolean): Seq[Setting[?]] = Seq(
  spliceFastOutput       := Def.uncached(ascentPreviewRoot.value / "fast.js"),
  ascentPreviewAutoServe := autoServe,
  ascentPreviewClasspath := Def.uncached((LocalProject("preview") / Compile / fullClasspath).value),
)

val ascentModules = Seq(
  "ascent-core",
  "ascent-css",
  "ascent-html",
  "ascent-js",
  "ascent-mount-engine",
  "ascent-dom-types",
  "ascent-dom-core",
  "ascent-dom-facade",
  "ascent-conduit",
  "ascent-history",
  "ascent-datastar",
  "ascent-datastar-http",
  "ascent-preview",
  "ascent-chekhov",
)

/** Published Specular jars depend on the Maven Central `ascent-*` release, but the docs modules `dependsOn` local
  * ascent, so coursier sees two versions of every ascent artifact. Under `early-semver` that is a hard conflict (local
  * `0.3.0-ci` vs a published `0.1.0`), so mark them `always` and let the local `dependsOn` win.
  *
  * Both the JVM (`_3`) and Scala.js (`_sjs1_3`) coordinates need an entry — `docsJS` resolves the latter, and a scheme
  * keyed on one does not cover the other.
  *
  * Do not use `excludeDependencies` here — it also strips the local `dependsOn` modules (conduit, datastar-http) from
  * the docs classpath.
  */
val docsDogfoodSettings = Def.settings(
  libraryDependencySchemes ++= ascentModules.flatMap { m =>
    Seq(
      "rocks.earlyeffect" % s"${m}_3"      % "always",
      "rocks.earlyeffect" % s"${m}_sjs1_3" % "always",
    )
  }
)

lazy val root = (project in file("."))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .aggregate(
    (domTypes.projectRefs ++ core.projectRefs ++ domFacade.projectRefs ++ domCore.projectRefs ++
      mountEngine.projectRefs ++ js.projectRefs ++
      domgen.projectRefs ++ css.projectRefs ++ conduitBridge.projectRefs ++ history.projectRefs ++
      html.projectRefs ++ datastar.projectRefs ++ datastarJs.projectRefs ++
      datastarHttp.projectRefs ++ datastarExample.projectRefs ++ datastarExampleServer.projectRefs ++
      hybridChat.projectRefs ++ hybridChatServer.projectRefs ++
      todoConduit.projectRefs ++ docs.projectRefs ++ preview.projectRefs ++
      ascentChekhov.projectRefs :+
      LocalProject("sbtAscentPreview")) *
  )
  .settings(
    name           := "ascent",
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
  .nativePlatform(scalaVersions = scalaVersions)

// --- ascent-domgen : pure-Scala generator, JVM tooling only (never a runtime dep) ---
lazy val domgen = (projectMatrix in file("domgen"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name           := "ascent-domgen",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    MyVersions.domgenLib,
    // Format generated output through the project's own .scalafmt.conf, so `domgen/run` emits
    // already-formatted files and formatting rules live in exactly one place. Scalameta ships
    // scalafmt for Scala 2.13; use it from this Scala 3 build via CrossVersion. Catalog excludes
    // live on the row; for3Use2_13 is not a zipx Cross, so it stays at the use site.
    libraryDependencies += MyVersions.moduleID(MyVersions.scalafmtDynamic).cross(CrossVersion.for3Use2_13),
    zioTestSettings,
    // sbt 2.x forks `run` with workingDirectory = baseDirectory.value (Defaults.forkOptionsTask),
    // which for this subproject is domgen/ — but Main.scala's vendored-data paths (data/webref/...)
    // are relative to the BUILD ROOT. Without this override, `domgen/run` silently can't find any
    // input file from a fresh checkout.
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
  .nativePlatform(scalaVersions = scalaVersions, javaTimePolyfill)

// --- ascent-dom-facade : our @js.native DOM facade (js only, no scalajs-dom) ---
//   Depends on dom-types so EnumAccessors.scala's additive typed-enum extensions can reference the
//   real Scala 3 enums generated there (Enums.scala) — see Renderer.enumAccessors/enumTypes.
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
//   CharacterData/Attr/Event plus every HTML/SVG element interface reachable via createElement) —
//   generated by domgen (Renderer.structuralTraits/memoryImpls) into generated/Elements.scala +
//   generated/ElementsMemory.scala. Two backends satisfy the SAME trait catalog: an in-memory
//   implementation (jvm/js/native, this module's default source tree — the memory impl has no
//   platform-specific code at all) and a JS adapter wrapping real dom-facade instances (js row
//   only, under src/main/scala-js).
//
//   `domFacade` has ONLY a js row (no jvm/native row exists at all) — projectMatrix's
//   `dependsOn(ProjectMatrix)` requires a matching row on every platform of the DEPENDENT, so a
//   matrix-wide `.dependsOn(domFacade)` fails to resolve on domCore's jvm/native rows ("no rows
//   were found in domFacade matching jvm/native"). The fix: attach the dependency to ONLY the js
//   row, via jsPlatform's `configure: Project => Project` overload (domFacade.js(scalaVersion)
//   resolves the concrete js-row Project) — not a matrix-wide `dependsOn`.
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
  .nativePlatform(scalaVersions = scalaVersions)

// --- ascent-mount-engine : the cross-platform Mount/Slot/Cleanup binding engine ---
//   The single UI-AST → DOM walker, rewritten against dom-core's platform-neutral structural
//   traits (not scalajs-dom directly), so ONE engine runs on jvm/js/native. Depends on core (the
//   UI AST + Squawk), dom-core (Node/Element/Document traits + in-memory backend), and css
//   (StyleSink). The JS-only rich-event path and browser <style> injection stay OUT of here — a
//   caller supplies an `EventCodec[E]` and a `StyleSink` per platform. jvm/js/native.
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
  .nativePlatform(scalaVersions = scalaVersions)

// --- ascent-js : DOM mount/binding engine + typed event DSL + DomStyleSink (js only) ---
//   Depends on `css` so DomStyleSink can implement StyleSink. CssClass is js-runnable from
//   here, but authoring stays in `css` so JVM/Native users can write stylesheets too.
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

// --- ascent-history : OPTIONAL URL session bound to Squawk. Location is path+query+hash; History is
//   push/replace/back/forward over a swappable backend (memory everywhere, window.history on JS).
//   Not a router: no matching, layouts, or loaders. Cross-built jvm/js/native so tests and SSR can
//   seed a memory session; the JS row also depends on dom-facade for the browser backend.
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
  .nativePlatform(scalaVersions = scalaVersions)

// --- ascent-conduit : OPTIONAL bridge between conduit's lens-keyed listener model and
//   ascent's Squawk reactive primitive. `c.squawk(lens)` returns a `UIO[Squawk[S]]` whose
//   value tracks that slice of the conduit model; updates flow through Squawk's dedup so
//   only real changes hit the DOM. Cross-built jvm/js/native to match conduit and core.
//
//   Depends on published conduit (rocks.earlyeffect). Stays a separate sub-module so users who don't
//   want conduit (or its ZIO transitive that core already needs) don't pull anything extra.
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
  .nativePlatform(scalaVersions = scalaVersions, javaTimePolyfill)

// --- ascent-css : CSS-in-Scala. Platform-neutral value layer (Declaration, Selector, Styles
//   property objects) + an abstract CssClass that injects via a StyleSink instance. The JS-only
//   DomStyleSink wires the actual <style> tag injection. Authoring is platform-neutral so SSR
//   can later render to a string by supplying a different StyleSink. dependsOn(core) so
//   CssClass.toAttr can produce an `ast.Attr` directly. Forward-compat with future generated
//   CSS: property objects always emit Declaration(name, value), the same shape a generator
//   produces.
// fastparse backs the runtime CSS3 selector parser (SelectorGrammar.scala / Sel.parse) — ascent's
// first genuine runtime dependency beyond ZIO, shipped jvm/js/native. Already used as a JVM-only
// domgen build-tool dependency; 3.1.1 also publishes real js/native artifacts, so this is a
// deliberate widening of scope, not a new library the team doesn't already have idioms for.
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
  .nativePlatform(scalaVersions = scalaVersions)

// --- ascent-html : UI AST -> HTML string renderer for SSR. NO separate walker any more — it MOUNTS
//   the `UI` into a disposable in-memory dom-core Document (via mount-engine's ONE Mount engine +
//   InMemoryDomOps), reflects live form-control value/checked into attributes for morph, then reads
//   `root.innerHTML`. So server output is produced by the exact same reconciler the browser uses —
//   the two can't drift. Depends on mount-engine (which brings core + dom-core + css). jvm/js/native.
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
  .nativePlatform(scalaVersions = scalaVersions, javaTimePolyfill)

// --- ascent-datastar : the datastar PROTOCOL core. DOM-free and platform-neutral so the routing /
//   decoding / merge logic is JVM-unit-testable: the decoded wire model (SignalPatch / ElementPatch),
//   a RemoteDialect SPI, the Datastar dialect, and SignalStore (named typed Squawk Sources fed by
//   incoming patches). Adds zio-json (NOT otherwise a runtime dep — only domgen uses it). dependsOn
//   core for Squawk. jvm/js/native; if zio-json's native artifact is unavailable, drop nativePlatform.
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
  .nativePlatform(scalaVersions = scalaVersions, javaTimePolyfill)

// --- ascent-datastar-js : the CLIENT RUNTIME. "ascent implements the datastar interface": opens an
//   EventSource, routes incoming patch-signals into Squawk Sources (Source.set -> ascent boundaries
//   repaint, focus preserved) and patch-elements into the DOM by selector+mode, and dispatches actions
//   back via fetch. JS only — it's the one piece that touches the live DOM facade. dependsOn datastar
//   (protocol + store) + js (Mount/Cleanup machinery) + domFacade (EventSource/fetch).
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

// --- ascent-datastar-http : server-side idiomatic wrapper over the official zio-http-datastar-sdk.
//   Makes the server "an ascent client": render an ascent UI subtree via ascent-html, push it as a
//   granular patch-elements (selector + mode) or patch-signals through the SDK's
//   ServerSentEventGenerator, and re-export the SDK's events{} / readSignals so datastar users keep
//   their idiom while authoring views in ascent's typed DSL. JVM only (the SDK + zio-http are JVM).
//   Pin the SDK to 3.11.0 — the newest version published on Maven Central (latest zio-http is 3.11.3
//   but the SDK lags). The real-server integration tests use zio-http's own Server/Client.
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

// --- ascent-preview : static file server + SSE tab reload (JVM). No Specular, no markdown. ---
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

// --- sbt-ascent-preview : enablePlugins(AscentPreviewPlugin) then `sbt ~<module>/ascentPreview`. ---
// Same source as project/AscentPreviewPlugin.scala (this repo cannot addSbtPlugin itself).
lazy val sbtAscentPreview = (project in file("sbt-ascent-preview"))
  .enablePlugins(SbtPlugin)
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .settings(
    name := "sbt-ascent-preview",
    // sbt 2.0.x Eval is Scala 3.8.4 (TASTy 28.8). ThisBuild is 3.9.0 for libraries; a 28.9
    // plugin jar is unreadable (#80). Leave this pin when sbt itself moves: TASTy is backward compatible.
    scalaVersion := "3.8.4",
    scalacOptions ++= commonScalacOptions,
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewPlugin.scala",
    Compile / unmanagedSources += (ThisBuild / baseDirectory).value / "project" / "AscentPreviewPort.scala",
    MyVersions.sbtPreviewLib,
  )

// --- ascent example: todo-conduit — TodoMVC over conduit (js only) ---
//   Lives under `example/<name>/`; more examples will sit alongside it. Depends on `js`
//   (binding engine), `css` (CSS-in-Scala authoring + DomStyleSink), and `conduitBridge`
//   (which transitively brings in conduit itself for app state). The examples are the
//   proving ground that all the optional layers compose without rough edges.
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

// --- ascent example: datastar-app — server-driven counter proving the full datastar loop ---
//   The CLIENT (js, pure ascent): a SignalStore fed by the datastar SSE stream drives ascent's own
//   reactive AST; a button POSTs an action back.
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

// --- ascent example: datastar-app SERVER — the zio-http backend (JVM). Holds the count, serves the
//   datastar SSE stream + the increment action via the ascent-datastar-http wrapper, with zio-http's
//   built-in brotli compression, and composes ascent-preview so the spliced client is same-origin. ---
lazy val datastarExampleServer = (projectMatrix in file("example/datastar-app-server"))
  .disablePlugins(chekhov.sbt.ChekhovPlugin)
  .dependsOn(datastarHttp, preview)
  .settings(
    name           := "ascent-datastar-example-server",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    // Netty's brotli compression needs the brotli4j native lib on the classpath (zio-http doesn't
    // bundle it). Without it, enabling brotli throws ClassNotFoundException at request time.
    MyVersions.brotli,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent example: hybrid-chat — a chat app whose CHROME is normal client-side ascent (inputs,
//   layout, typing indicator) and whose MESSAGE LIST is a server-driven `serverRegion`. The CLIENT
//   (js) declares the region + UI; the SERVER renders message rows via ascent-html and pushes them
//   with `patchRegion`. Proves the hybrid: client-owned reactivity + server-owned region, together. ---
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

// The hybrid-chat SERVER (JVM): ChatRoom state + SSE routes; renders message rows via ascent-html and
// pushes them into the client's `serverRegion("messages")` with `AscentDatastar.patchRegion`.
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
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(datastarHttp.jvm(scala3Version), preview.jvm(scala3Version))
        .enablePlugins(SpecularPlugin, AscentPreviewPlugin)
        .settings(
          docsDogfoodSettings,
          MyVersions.docsJvm,
          // specular-site (via the theme) still declares zio-json 0.9.x
          dependencyOverrides += MyVersions.moduleID(MyVersions.zioJson),
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
          // Dynver `-ci` / SNAPSHOT: show the previous stable tag in install snippets.
          specularDisplayVersion := {
            val fallback = previousStableVersion.value.getOrElse("<version>")
            (v: String) =>
              if v.endsWith("-ci") || v.endsWith("-SNAPSHOT") then fallback else v
          },
          // Link the JS client and write a marker path BuildSite copies into assets/client.js.
          specularJsLink := Def.uncached {
            (LocalProject("docsJS") / Compile / fastLinkJS).value
            val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
            val mainJs = outDir / "main.js"
            if (!mainJs.exists) then
            sys.error(
              s"Expected $mainJs after fastLinkJS; directory contains: " +
                Option(outDir.list).toSeq.flatten.mkString(", ")
            )
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
          docsDogfoodSettings,
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

// ascentChekhov is not in ascentMatrices: its JS row is ChekhovJSEnv (not jsdom) and must
// stay off testJS. The JVM row is browser-free selector tests and joins testJVM here.
addCommandAlias("testJVM", ascentPlatformTestCommand(_.jvm) + "; ascentChekhov/test")
addCommandAlias("testJS", ascentPlatformTestCommand(_.js))
addCommandAlias("testNative", ascentPlatformTestCommand(_.native))

lazy val e2eStage = taskKey[Unit]("Stage example preview trees for Chekhov e2e")

// Browser suites. Not aggregated so library `testFull` stays browser-free.
// --- ascent-chekhov : typed Chekhov locators over the HTML lattice (jvm + js). ---
//   Shared sources are the TagHandle typeclass + handle algebra (no live DOM, no Chekhov
//   imports). The JVM row interprets handles as Playwright selector strings via chekhov-core.
//   The JS row mounts a UI into chekhov-dom's iframe withRoot and talks to live ascent.dom
//   nodes. Not in ascentMatrices: JS tests need ChekhovJSEnv (see testJVM / e2e capability).
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
          // Do not enable ChekhovPlugin here: it sets Test / fork := true, which is a JVM
          // suite setting. JSEnv is constructed directly (same Firefox pin as e2e).
          Test / jsEnv := Def.uncached(
            ChekhovJSEnv(browser = ChekhovBrowser.Firefox, headless = true, keepOpen = false)
          ),
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
      (LocalProject("todoConduitJS") / ascentPreviewStage).value
      (LocalProject("datastarExampleJS") / ascentPreviewStage).value
      (LocalProject("hybridChatJS") / ascentPreviewStage).value
      ()
    },
    Test / javaOptions += s"-Dascent.repoRoot=${(ThisBuild / baseDirectory).value.getAbsolutePath}",
    // CompileAnalysis has no JsonFormat; sbt 2 caches task outputs unless we opt out.
    Test / compile := Def.uncached((Test / compile).dependsOn(e2eStage).value),
  )
