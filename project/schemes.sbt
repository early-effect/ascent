// sbt-chekhov 0.1.0 and sbt-zipx 0.11.0 take zio-json 1.1.0; leftover 0.9/0.10 pins still need a scheme.
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"
