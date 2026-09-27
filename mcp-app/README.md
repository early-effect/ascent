# ascent-mcp-app

An [MCP App](https://github.com/modelcontextprotocol/ext-apps) view, authored in ascent over heddle's view bridge
(`heddle-mcp-apps`). A view is a page inside the host's sandboxed iframe, so this module is JS only.

```scala
val app = McpApp(counter).view { (run, bridge) =>
  E.div(
    E.output(run.map { case Run.Returned(_, count) => count.value.toString; case _ => "…" }),
    E.button(Ev.onClick(_ => bridge.call(_.inc)(()).ignore), "+"),
  )
}
```

`McpApp(shed)` pins every type from the shed before `view` takes its lambda, so the lambda needs no ascription:
`run` is a `Squawk[Run[In, Err, Out]]` of the launch tool's lifecycle, typed from the shed's launch grant, and
`bridge.call(_.inc)` picks a grant from the shed, with a typed input and a typed output or declared error.

`app.mount(PostMessageBridge.toParent, container, AppInfo("counter-view", "1"))` connects to the host and mounts
the view for the life of a scope:

- The launch tool's run and the host context are squawks the view renders.
- Each standard theme variable the host sends (`--color-background-primary` and the rest) is set as a custom
  property on `:root`, so ascent-css reads it with `var(...)`.
- A scoped `ResizeObserver` reports the container's size to the host (`ui/notifications/size-changed`).
- The host's `ui/resource-teardown` unmounts the view and interrupts its in-flight calls before the view answers.
