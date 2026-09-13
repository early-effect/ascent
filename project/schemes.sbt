// sbt-chekhov 0.1.0 pulls zio-json 1.1.0 onto the metabuild; zipx-core still pins 0.10.0.
ThisBuild / libraryDependencySchemes += "dev.zio" %% "zio-json" % "always"
