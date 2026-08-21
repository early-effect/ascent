# ascent-preview

Path-jailed **static file server** plus SSE tab reload. The process stays up. Rebuild HTML/JS in
place; rewrite `assets/dev-stamp` to poke the browser.

This is a published JVM library (`rocks.earlyeffect %% "ascent-preview"`), not an ascent-internal
helper. Pair it with **`sbt-ascent-preview`** so the command is the same in every repo:

```bash
sbt ~<module>/ascentPreview
```

`~` is sbt's file watch. The task does **not** restart Preview. One-shot (no watch):
`sbt <module>/ascentPreview`.

## Install

```scala
libraryDependencies += "rocks.earlyeffect" %% "ascent-preview" % "<version>"
```

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-ascent-preview" % "<version>")

lazy val appJS = (project in file("app"))
  .enablePlugins(ScalaJSPlugin, AscentPreviewPlugin)
```

Tab client: `ascent.js.DevReload.install()` (localhost only). Specular's `SpecularClient` already
calls it.

## Contract

| Piece | Default |
| --- | --- |
| Root | `<sources' parent>/target/preview` |
| Stamp | `assets/dev-stamp` (content change, not mtime) |
| SSE | `GET /__ascent/reload` |
| Port | `8765`, or `"auto"` (first free `>= 8700`) |
| CORS | off |

`Preview.routes(PreviewConfig(...))` composes into a zio-http app (datastar / hybrid). Then set
`ascentPreviewAutoServe := false` on the JS module so you do not start a second PreviewMain.

`ascentPreviewAutoOpen := true` on a module opens the preview URL once PreviewMain binds. Default
off. Watch rebuilds do not open another tab.

`ascentPreviewPort := AscentPreviewPort(8701)` or `ascentPreviewPort := AscentPreviewPort("auto")`.
`"auto"` is the first free port at or above 8700. Ports must be unprivileged (`1024`-`65535`);
`"noo auto"` and `80` do not compile.

CLI: `PreviewMain <port> <siteRoot> [--open]`.

Do not `sbt ~…/runReload` or otherwise watch the server process.

Full recipes: [Preview](https://www.earlyeffect.rocks/ascent/preview.html) (this repo's docs page).
