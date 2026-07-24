// projectMatrix is built into sbt 2.x (sbt-projectmatrix was in-sourced and archived), so no
// plugin is needed for `projectMatrix`/`jvmPlatform`/`jsPlatform`/`nativePlatform`.
addSbtPlugin("org.scala-js"      % "sbt-scalajs"      % "1.22.0")
addSbtPlugin("org.scala-native"  % "sbt-scala-native" % "0.5.12")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"     % "2.6.1")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"     % "0.14.7")
addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci"    % "0.1.0")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"          % "2.3.1")
addSbtPlugin("rocks.earlyeffect" % "sbt-specular"     % "0.6.2")
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx"         % "0.0.10")
//addSbtPlugin("org.xerial.sbt"   % "sbt-sonatype"      % "3.12.2")

// zipx bundles sbt-remote-cache; compiler-interface is on both sbt-2.x and zinc-1.x schemes.
libraryDependencySchemes += "org.scala-sbt" % "compiler-interface" % "always"

// jsdom-backed JS test environment for the dom-facade and js modules' Test scope.
// `scalajs-env-jsdom-nodejs` has no Scala 3 / sbt-2 artifact, so we vendor its single source
// file (project/JSDOMNodeJSEnv.scala). It compiles against the org.scalajs.jsenv API that
// sbt-scalajs already puts on the meta-build classpath (scalajs-js-envs + scalajs-env-nodejs,
// the latter bringing jimfs transitively) — hence no extra libraryDependencies are needed.
