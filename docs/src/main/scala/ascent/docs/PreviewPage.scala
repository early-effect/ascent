package ascent.docs

import specular.*

/** Local static serve + SSE tab reload. Markdown only: this is a JVM server, not a browser demo. */
object PreviewPage extends DocSpec:

  def doc = page("Preview")(
    md"""
`ascent-preview` is a **published JVM library**: path-jailed static files plus an SSE endpoint that
fires when **`assets/dev-stamp` contents** change. The tab reloads. The Preview process does not.

That is the whole contract. HTML and JS may churn on disk; rewrite the stamp to poke the browser.
Never watch a task that kills the server (`~runReload`, `~preview/run`).

The command to remember is **`sbt ~<module>/ascentPreview`**. `~` is sbt's file watch on a task
that keeps Preview up. One-shot (start once, no watch): `sbt <module>/ascentPreview`.
""",
    section("Install")(
      md"""
Library (JVM):

```scala
libraryDependencies += "rocks.earlyeffect" %% "ascent-preview" % "<version>"
```

Plugin (the command):

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-ascent-preview" % "<version>")
```

Then `enablePlugins(AscentPreviewPlugin)` on the module you type. Specular docs modules will get
that by default once sbt-specular requires this plugin; until then, enable it on `docs` and set
`ascentPreviewRoot` to the site directory and `ascentPreviewRebuild` to `specularSiteDev`.
"""
    ),
    section("Scala.js app")(
      md"""
```scala
lazy val todo = (project in file("example/todo"))
  .enablePlugins(ScalaJSPlugin, AscentPreviewPlugin)
  .settings(
    // JS projects have no PreviewMain on Compile. Point at a JVM ascent-preview classpath:
    ascentPreviewClasspath := (LocalProject("preview") / Compile / fullClasspath).value,
  )
```

```bash
sbt ~todoJS/ascentPreview
# open http://localhost:8765
```

Default rebuild is `ascentPreviewStage`: `spliceFast` (if sbt-splice is on the project), copy
`index.html`, write `assets/dev-stamp`. Override `ascentPreviewBundle` for plain `fastLinkJS`.
"""
    ),
    section("Specular docs")(
      md"""
Docs are the same command. The rebuild task writes `target/site` (including the stamp) instead of
an example `target/preview`.

```scala
.enablePlugins(SpecularPlugin, AscentPreviewPlugin)
.settings(
  ascentPreviewRoot     := specularSiteDirectory.value,
  ascentPreviewRebuild  := specularSiteDev.value,
  ascentPreviewAutoOpen := true,               // open the tab once Preview binds
  ascentPreviewPort     := AscentPreviewPort("auto"), // first free port >= 8700
)
```

```bash
sbt ~docs/ascentPreview
# open the localhost URL sbt prints (port is auto)
```

`specularServe` stays a blocking one-shot of an already-built tree. The edit loop is only
`ascentPreview`.
"""
    ),
    section("Compose into zio-http")(
      md"""
When a JVM app already serves the staged tree (datastar / hybrid examples), do not start a second
PreviewMain. Set `ascentPreviewAutoServe := false` on the JS module and keep the API server up:

```scala
Preview.routes(PreviewConfig(root = previewRoot, port = 8080)) ++ apiRoutes
```

```bash
sbt ~datastarExampleJS/ascentPreview   # stage + stamp only
sbt datastarExampleServer/run          # Preview.routes + API on :8080
```

The server process stays up. Only the files under `target/preview` change.
"""
    ),
    section("Tab client")(
      md"""
From `ascent-js` (localhost only; inert on GitHub Pages):

```scala
DevReload.install()   // EventSource /__ascent/reload → location.reload()
```

Specular's `SpecularClient.mountAll` already calls this. Do not also poll `assets/dev-stamp`.

Without Scala.js, a tiny snippet in `index.html`:

```html
<script>
if (["localhost","127.0.0.1","[::1]"].includes(location.hostname)) {
  const es = new EventSource("/__ascent/reload");
  const reload = () => location.reload();
  es.addEventListener("reload", reload);
  es.addEventListener("message", reload);
  es.addEventListener("error", () => es.close());
}
</script>
```
"""
    ),
    section("Config and jail")(
      md"""
`PreviewConfig(root, port = 8765, stamp = assets/dev-stamp, reloadPath = __ascent/reload, cors = false, openBrowser = false)`.
CORS is off by default so same-origin example servers stay strict.

Paths are jailed: `..` is rejected, and the resolved file must be a canonical descendant of `root`.
CLI: `PreviewMain <port> <siteRoot> [--open]` (default `8765` and `target/site`). `--open` is
what `ascentPreviewAutoOpen := true` passes so the tab opens once the socket is bound, not on every
`~` rebuild.

Module bind: `ascentPreviewPort := AscentPreviewPort(8701)` or
`ascentPreviewPort := AscentPreviewPort("auto")` (first free port `>= 8700`).
Neotype rejects `"noo auto"` and privileged ports like `80` at compile time.
"""
    ),
    section("Anti-pattern")(
      md"""
```bash
sbt ~docs/Test/runReload     # restarts the Preview JVM on every compile
sbt ~preview/run             # same: the process is the watch target
```

Watch the **rebuild** (`ascentPreview` / `ascentPreviewStage` / `specularSite`), not the server.
"""
    ),
  )
end PreviewPage
