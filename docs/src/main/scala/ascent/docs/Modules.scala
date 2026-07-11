package ascent.docs

import specular.*

/** Published modules: what to depend on vs internals. */
object Modules extends DocSpec:

  def doc = page("Modules")(
    md"""
ascent is a multi-module toolkit. Most apps need only a few artifacts; others are plumbing
published so consumers resolve transitively.

## App-facing

| Artifact | Use when |
| --- | --- |
| `ascent-core` | Always: Squawk, UI AST, DSL |
| `ascent-js` | Browser mount / binding |
| `ascent-css` | Typed CSS-in-Scala |
| `ascent-conduit` | Optional conduit `Ctx[M]` |
| `ascent-html` | SSR string renderer |
| `ascent-datastar` | Datastar protocol + SignalStore |
| `ascent-datastar-js` | Browser datastar runtime |
| `ascent-datastar-http` | zio-http datastar server bridge |

## Internals (transitive)

`ascent-dom-types`, `ascent-dom-facade`, `ascent-dom-core`, and `ascent-mount-engine` are engine
internals. Depend on them only if you are extending the platform; ordinary apps get them
transitively.

- `domgen`: JVM generator; never a runtime dep
- `example/*`: Vite apps (todo-conduit, datastar-app, hybrid-chat)
"""
  )
end Modules
