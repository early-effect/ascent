package ascent.docs

import specular.site.CodeSnippet

/** Index install fences. Each artifact names its own release. */
object Install:

  def snippets(org: String, versions: ModuleVersions): Vector[CodeSnippet] =
    Vector(
      CodeSnippet(
        "Install (core + browser)",
        s"""libraryDependencies ++= Seq(
           |  "$org" %%% "ascent-core" % "${versions.core}",
           |  "$org" %%% "ascent-js"   % "${versions.js}", // Scala.js mount engine
           |  "$org" %%% "ascent-css"  % "${versions.css}", // optional typed CSS
           |)""".stripMargin,
      ),
      CodeSnippet(
        "Optional modules",
        s"""libraryDependencies ++= Seq(
           |  "$org" %%% "ascent-conduit"       % "${versions.conduit}", // Ctx[M] state bridge
           |  "$org" %%% "ascent-history"       % "${versions.history}", // Location as a Squawk
           |  "$org" %%% "ascent-element"       % "${versions.element}", // custom elements
           |  "$org" %%% "ascent-mcp-app"       % "${versions.mcpApp}", // MCP App view
           |  "$org" %%% "ascent-mcp-host"      % "${versions.mcpHost}", // MCP App host page
           |  "$org" %%% "ascent-datastar"      % "${versions.datastar}", // protocol + SignalStore
           |  "$org" %%% "ascent-datastar-js"   % "${versions.datastarJs}", // browser datastar
           |  "$org" %%  "ascent-html"          % "${versions.html}", // SSR
           |  "$org" %%  "ascent-datastar-http" % "${versions.datastarHttp}", // server datastar
           |  "$org" %%  "ascent-preview"       % "${versions.preview}", // local static + SSE reload
           |  "$org" %%% "ascent-chekhov"       % "${versions.chekhov}" % Test, // typed Chekhov locators
           |)""".stripMargin,
      ),
      CodeSnippet(
        "Local preview plugin",
        s"""addSbtPlugin("$org" % "sbt-ascent-preview" % "${versions.preview}")
           |// enablePlugins(AscentPreviewPlugin) on the module; then:
           |// sbt todoConduitJS/ascentPreview   or   sbt docs/ascentPreview""".stripMargin,
      ),
    )
end Install
