package ascent.docs

import specular.*

/** Published modules: what to depend on vs internals. */
object Modules extends DocSpec:

  def doc = page("Modules")(
    md"""
ascent is a multi-module toolkit. Most apps need only a few artifacts; others are plumbing
published so consumers resolve transitively.

## App-facing

| Artifact | Version | Use when |
| --- | --- | --- |
| `ascent-core` | ${Released.core} | Always: Squawk, UI AST, DSL |
| `ascent-js` | ${Released.js} | Browser mount / binding |
| `ascent-css` | ${Released.css} | Typed CSS-in-Scala |
| `ascent-conduit` | ${Released.conduitBridge} | Optional conduit `Ctx[M]` |
| `ascent-history` | ${Released.history} | Optional URL session as a Squawk (`History` / `Location`) |
| `ascent-element` | ${Released.element} | Custom elements (JS): define a name once per page, and follow each instance's connections as a stream |
| `ascent-mcp-app` | ${Released.mcpApp} | An MCP App view (JS): renders the launch tool's `Run`, calls the shed's grants, follows the host |
| `ascent-mcp-host` | ${Released.mcpHost} | An MCP App host page (JS): `<ascent-mcp-view>` frames a server's view in heddle's relay, asks the user about each call, and tears the view down |
| `ascent-html` | ${Released.html} | SSR string renderer |
| `ascent-datastar` | ${Released.datastar} | Datastar protocol + SignalStore |
| `ascent-datastar-js` | ${Released.datastarJs} | Browser datastar runtime |
| `ascent-datastar-http` | ${Released.datastarHttp} | heddle Datastar server bridge |
| `ascent-preview` | ${Released.preview} | Local static serve + SSE reload; optional extra routes / sidecar. See [Preview](preview.html). |
| `ascent-chekhov` | ${Released.ascentChekhov} | Typed Chekhov locators: JSEnv live handles + JVM `Page` selectors |
| `sbt-ascent-preview` | ${Released.preview} | `enablePlugins(AscentPreviewPlugin)` then `sbt <module>/ascentPreview` |

## Internals (transitive)

`ascent-dom-types`, `ascent-dom-facade`, and `ascent-dom-core` publish together at ${Released.dom}.
`ascent-mount-engine` publishes at ${Released.mountEngine}. Depend on them only if you are
extending the platform; ordinary apps get them transitively.

- `domgen`: JVM generator; never a runtime dep
- `example/*`: splice + preview apps (todo-conduit, datastar-app, hybrid-chat, mcp-host)
"""
  )
end Modules
