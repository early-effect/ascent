import scala.collection.immutable.ListMap

val scala3Version = "3.8.4"
val zioVersion    = "2.1.26"

// sbt 2.x scopes bare build.sbt settings to ThisBuild, so these apply build-wide to every module.
scalaVersion         := scala3Version
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

// zipx CI: Aggregate verify + Central publish + Specular Pages. Same-name caps replace built-ins.
zipxJavaVersion      := "25"
zipxTestTask         := "testFull"
zipxWorkflowDispatch := true
zipxScalaSteward     := true

/** Node (jsdom/canvas) + Scala Native apt deps for the Aggregate test job. */
val ascentCiSetup: StepContext => List[Step] = _ =>
  List(
    Step(
      name = Some("Set up Node"),
      uses = Some("actions/setup-node@48b55a011bda9f5d6aeb4c2d9c7362e8dae4041e"), // v6.4.0
      `with` = ListMap("node-version" -> "24", "cache" -> "npm"),
    ),
    Step(
      name = Some("Install canvas build dependencies"),
      run = Some(
        "sudo apt-get update && sudo apt-get install -y libcairo2-dev libpango1.0-dev libjpeg-dev libgif-dev librsvg2-dev"
      ),
    ),
    Step(
      name = Some("Install Node dependencies (jsdom, canvas)"),
      run = Some("npm ci"),
    ),
    Step(
      name = Some("Install Scala Native build dependencies"),
      run = Some("sudo apt-get install -y clang libstdc++-12-dev libgc-dev libunwind-dev"),
    ),
  )

zipxCapabilities ++= Seq(
  Capability.once("fmt", "scalafmtCheckAll; zipxWorkflowCheck"),
  Capability.once(
    name = "test",
    command = "testFull",
    needsCapabilities = List("fmt"),
    extraSteps = ascentCiSetup,
    env = Map("JAVA_OPTS" -> EnvValue.plain("-Dfile.encoding=UTF-8")),
  ),
  ZipxCentral.release,
  ZipxDocs.pages(),
  Capability.once(
    name = "dependency-submission",
    // zipx Once jobs always emit an sbt step. Run the action in extraSteps *before* that step: an earlier
    // `sbt about` would start a server without GITHUB_TOKEN, and the action's later sbt client would reuse it
    // (snapshot generates, submit then fails with "Missing environment variable GITHUB_TOKEN").
    command = "about",
    needsCapabilities = List("test"),
    permissions = Map("contents" -> "write"),
    extraSteps = _ =>
      List(
        Step(
          name = Some("Submit dependency graph"),
          uses = Some("scalacenter/sbt-dependency-submission@d84eef4c09e633bcf5f113bcad7fd5e9af1baee9"), // v3.2.3
        )
      ),
  ),
)

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
val javaTimePolyfill = Def.settings(
  libraryDependencies ++= Seq(
    "io.github.cquiroz" %% "scala-java-time"      % "2.7.0",
    "io.github.cquiroz" %% "scala-java-time-tzdb" % "2.7.0",
  )
)

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

// zio-test deps, shared by every module. Defined as a Def.settings block (not a bare Seq) so the
// per-project platform suffix that `%%` appends in sbt 2.x is resolved at each module's scope.
// The ZTestFramework registers itself automatically via zio-test-sbt, so no testFrameworks wiring.
val zioTestSettings = Def.settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  )
)

// jsdom-backed test environment for JS modules that need a real DOM (dom-facade engine
// facade tests, ascent-js mount/binding tests). The dependency itself comes from
// project/plugins.sbt; we only need to wire up the jsEnv here. Requires `npm install jsdom`
// in the project root before tests run.
// `JSEnv` has no JsonFormat, and sbt 2.x caches setting values by default — opt this one out of
// caching (it's a fresh, non-serializable env instance) rather than invent a bogus codec.
val jsdomTestEnv = Def.settings(
  Test / jsEnv := Def.uncached(new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv())
)

val specularVersion = "0.6.2"

/** Published Specular jars depend on Maven Central ascent 0.1.0; the docs module dependsOn local
  * ascent instead. Strip every ascent-* transitive so coursier does not see two versions under
  * early-semver (CI dynver-ci is often `0.1.0-ci`, which conflicts with a published `0.1.0`). */
val ascentMavenExclusions = Seq(
  ExclusionRule("rocks.earlyeffect", "ascent-core_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-core_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-css_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-css_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-html_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-html_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-js_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-mount-engine_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-mount-engine_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-dom-types_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-dom-types_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-dom-core_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-dom-core_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-dom-facade_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-conduit_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-conduit_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-datastar_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-datastar_sjs1_3"),
  ExclusionRule("rocks.earlyeffect", "ascent-datastar-http_3"),
)

def specularLib(artifact: String) =
  ("rocks.earlyeffect" %% artifact % specularVersion).excludeAll(ascentMavenExclusions*)

/** Prefer local ascent over any Maven copy that still slips through specularLib exclusions.
  * Do not use excludeDependencies here: it also strips local dependsOn modules (conduit, datastar-http)
  * from the docs classpath.
  */
val docsDogfoodSettings = Def.settings(
  libraryDependencySchemes ++= Seq(
    "rocks.earlyeffect" %% "ascent-core"          % "always",
    "rocks.earlyeffect" %% "ascent-css"           % "always",
    "rocks.earlyeffect" %% "ascent-html"          % "always",
    "rocks.earlyeffect" %% "ascent-js"            % "always",
    "rocks.earlyeffect" %% "ascent-mount-engine"  % "always",
    "rocks.earlyeffect" %% "ascent-dom-types"     % "always",
    "rocks.earlyeffect" %% "ascent-dom-core"      % "always",
    "rocks.earlyeffect" %% "ascent-dom-facade"    % "always",
    "rocks.earlyeffect" %% "ascent-conduit"       % "always",
    "rocks.earlyeffect" %% "ascent-datastar"      % "always",
    "rocks.earlyeffect" %% "ascent-datastar-http" % "always",
  ),
)

lazy val root = (project in file("."))
  .aggregate(
    (domTypes.projectRefs ++ core.projectRefs ++ domFacade.projectRefs ++ domCore.projectRefs ++
      mountEngine.projectRefs ++ js.projectRefs ++
      domgen.projectRefs ++ css.projectRefs ++ conduitBridge.projectRefs ++
      html.projectRefs ++ datastar.projectRefs ++ datastarJs.projectRefs ++
      datastarHttp.projectRefs ++ datastarExample.projectRefs ++ datastarExampleServer.projectRefs ++
      hybridChat.projectRefs ++ hybridChatServer.projectRefs ++
      todoConduit.projectRefs ++ docs.projectRefs) *
  )
  .settings(
    name           := "ascent",
    publish / skip := true,
    test / skip    := true,
  )

// --- ascent-dom-types : generated element/attr/event defs + codecs (zero deps, jvm/js/native) ---
lazy val domTypes = (projectMatrix in file("dom-types"))
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
  .settings(
    name           := "ascent-domgen",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio"     %% "zio-json"  % "0.9.2",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      // Format generated output through the project's own .scalafmt.conf, so `domgen/run` emits
      // already-formatted files and formatting rules live in exactly one place. Scalameta ships
      // scalafmt for Scala 2.13; use it from this Scala 3 build via CrossVersion. scalafmt-dynamic
      // loads the real formatter in its own classloader, so its 2.13 transitives don't belong on our
      // classpath — exclude scala-collection-compat_2.13, which clashes with the _3 already present.
      ("org.scalameta" %% "scalafmt-dynamic" % "3.11.2")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-collection-compat_2.13"),
    ),
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
  .dependsOn(domTypes)
  .settings(
    name := "ascent-core",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "dev.zio" %% "zio" % zioVersion,
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
  .dependsOn(core, domFacade, css, mountEngine)
  .settings(
    name := "ascent-js",
    scalacOptions ++= commonScalacOptions,
    zioTestSettings,
    jsdomTestEnv,
  )
  .jsPlatform(scalaVersions = scalaVersions)

// --- ascent-conduit : OPTIONAL bridge between conduit's lens-keyed listener model and
//   ascent's Squawk reactive primitive. `c.squawk(lens)` returns a `UIO[Squawk[S]]` whose
//   value tracks that slice of the conduit model; updates flow through Squawk's dedup so
//   only real changes hit the DOM. Cross-built jvm/js/native to match conduit and core.
//
//   Depends on the published conduit (which uses Scala 3.6.3 but TASTy forward-compat
//   typically lets 3.8.3 consume it). Stays a separate sub-module so users who don't want
//   conduit (or its ZIO transitive that core already needs) don't pull anything extra.
lazy val conduitBridge = (projectMatrix in file("conduit"))
  .dependsOn(core)
  .settings(
    name := "ascent-conduit",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "io.github.russwyte" %% "conduit" % "0.0.6",
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
  .dependsOn(core)
  .settings(
    name := "ascent-css",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "com.lihaoyi" %% "fastparse" % "3.1.1",
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
  .dependsOn(core)
  .settings(
    name := "ascent-datastar",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "dev.zio" %% "zio-json" % "0.9.2",
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
  .dependsOn(html, datastar)
  .settings(
    name := "ascent-datastar-http",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "dev.zio" %% "zio-http-datastar-sdk" % "3.11.3",
    zioTestSettings,
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent example: todo-conduit — TodoMVC over conduit, bundled by Vite (js only) ---
//   Lives under `example/<name>/`; more examples will sit alongside it. Depends on `js`
//   (binding engine), `css` (CSS-in-Scala authoring + DomStyleSink), and `conduitBridge`
//   (which transitively brings in conduit itself for app state). The examples are the
//   proving ground that all the optional layers compose without rough edges.
lazy val todoConduit = (projectMatrix in file("example/todo-conduit"))
  .dependsOn(js, css, conduitBridge)
  .settings(
    name           := "ascent-todo-conduit",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )
  .jsPlatform(scalaVersions = scalaVersions)

// --- ascent example: datastar-app — server-driven counter proving the full datastar loop ---
//   The CLIENT (js, pure ascent): a SignalStore fed by the datastar SSE stream drives ascent's own
//   reactive AST; a button POSTs an action back. Bundled by Vite like todo-conduit.
lazy val datastarExample = (projectMatrix in file("example/datastar-app"))
  .dependsOn(datastarJs, css)
  .settings(
    name           := "ascent-datastar-example",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
  )
  // Vite bundles ES modules; emit ESModule output (the link honors this — verified the bundle has no
  // NoModule IIFE wrapper and esbuild parses it as ESM).
  .jsPlatform(
    scalaVersions = scalaVersions,
    Seq(
      scalaJSUseMainModuleInitializer := true,
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    ),
  )

// --- ascent example: datastar-app SERVER — the zio-http backend (JVM). Holds the count, serves the
//   datastar SSE stream + the increment action via the ascent-datastar-http wrapper, with zio-http's
//   built-in brotli compression. Run with `sbt datastarExampleServer/run` alongside Vite. ---
lazy val datastarExampleServer = (projectMatrix in file("example/datastar-app-server"))
  .dependsOn(datastarHttp)
  .settings(
    name           := "ascent-datastar-example-server",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    // Netty's brotli compression needs the brotli4j native lib on the classpath (zio-http doesn't
    // bundle it). Without it, enabling brotli throws ClassNotFoundException at request time.
    libraryDependencies += "com.aayushatharva.brotli4j" % "brotli4j" % "1.23.0",
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent example: hybrid-chat — a chat app whose CHROME is normal client-side ascent (inputs,
//   layout, typing indicator) and whose MESSAGE LIST is a server-driven `serverRegion`. The CLIENT
//   (js) declares the region + UI; the SERVER renders message rows via ascent-html and pushes them
//   with `patchRegion`. Proves the hybrid: client-owned reactivity + server-owned region, together. ---
lazy val hybridChat = (projectMatrix in file("example/hybrid-chat"))
  .dependsOn(datastarJs, css)
  .settings(
    name           := "ascent-hybrid-chat",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
  )
  .jsPlatform(
    scalaVersions = scalaVersions,
    Seq(
      scalaJSUseMainModuleInitializer := true,
      scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    ),
  )

// The hybrid-chat SERVER (JVM): ChatRoom state + SSE routes; renders message rows via ascent-html and
// pushes them into the client's `serverRegion("messages")` with `AscentDatastar.patchRegion`.
lazy val hybridChatServer = (projectMatrix in file("example/hybrid-chat-server"))
  .dependsOn(datastarHttp)
  .settings(
    name           := "ascent-hybrid-chat-server",
    publish / skip := true,
    test / skip    := true,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies += "com.aayushatharva.brotli4j" % "brotli4j" % "1.23.0",
  )
  .jvmPlatform(scalaVersions = scalaVersions)

// --- ascent-docs : Specular DocSpecs + static site (JVM) and interactive client (JS) ---
lazy val docs: ProjectMatrix = (projectMatrix in file("docs"))
  .dependsOn(core, css, conduitBridge, html, datastar)
  .settings(
    name           := "ascent-docs",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    description    := "Effect-native reactive UI for Scala 3; docs site",
  )
  .jvmPlatform(
    scalaVersions,
    Nil,
    (p: Project) =>
      p.dependsOn(datastarHttp.jvm(scala3Version))
        .enablePlugins(SpecularPlugin)
        .settings(
          docsDogfoodSettings,
          libraryDependencies ++= Seq(
            specularLib("specular-core"),
            specularLib("specular-zio-test"),
            specularLib("specular-site"),
            specularLib("early-effect-docs-theme"),
            "dev.zio" %% "zio-test"     % zioVersion,
            "dev.zio" %% "zio-test-sbt" % zioVersion,
          ),
          zioTestSettings,
          Compile / mainClass     := Some("ascent.docs.ServeSite"),
          run / mainClass         := Some("ascent.docs.ServeSite"),
          specularBuildMain       := "ascent.docs.BuildSite",
          specularSiteDirectory   := (ThisBuild / baseDirectory).value / "target" / "site",
          // Link the JS client and write a marker path BuildSite copies into assets/client.js.
          specularJsLink := Def.uncached {
            (LocalProject("docsJS") / Compile / fastLinkJS).value
            val outDir = (LocalProject("docsJS") / Compile / fastLinkJSOutput).value
            val mainJs = outDir / "main.js"
            if !mainJs.exists then
              sys.error(
                s"Expected $mainJs after fastLinkJS; directory contains: " +
                  Option(outDir.list).toSeq.flatten.mkString(", ")
              )
            val marker = (ThisBuild / baseDirectory).value / "target" / "specular-client-js.path"
            IO.write(marker, mainJs.getAbsolutePath)
            ()
          },
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
          libraryDependencies ++= Seq(
            specularLib("specular-core"),
            "dev.zio" %% "zio-test" % zioVersion,
          ),
          scalaJSUseMainModuleInitializer := true,
          scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
          Compile / mainClass := Some("ascent.docs.ClientMain"),
        ),
  )
