# ascent-mcp-host

The host page's side of [MCP Apps](https://github.com/modelcontextprotocol/ext-apps): an `<ascent-mcp-view>` custom
element that frames one server's view in heddle's relay (`heddle-mcp-apps-frame`) and draws its chrome in the element's
shadow root, a border, the view's state, and the question for each call the view asks to make. JS only.

```scala
for
  views <- McpViews.define(host = pageOrigin)            // once per page
  mount <- appsHost.mount(server, launched)              // heddle's host kit, after the model's tool call
  view  <- views.frame(mount, RelayMode.Opaque)          // an unattached <ascent-mcp-view>
  _     <- ZIO.succeed(slot.appendChild(view.element))   // framed once the page puts it in
yield view
```

`frame` needs a `Scope`, heddle's `Audit`, and a `ConsentMemory`: the page's one memory of what the user allowed for
the session.

## What the element does

- **Isolation stays in heddle.** A shadow root shares the page's origin, realm, and CSP, so it is packaging. The view
  runs in heddle's double iframe (the relay, then the view), and the element only hands heddle a slot to put it in.
- **The question shows on the frame that asked.** Each element is its own frame's consent gate, so a call the view
  asks to make appears above that view's frame, with its arguments, never over it. "Allow for this session" is kept
  in the page's `ConsentMemory`, so it holds for every element on the page.
- **Buttons arm late.** A question's buttons stay disabled for `armAfter` (one second by default, as browsers hold
  their own permission prompts), so a click aimed at the view cannot land on "Allow". One question shows at a time.
- **`teardown(reason)` ends a view the way MCP Apps asks a host to.** The question is withdrawn, then the view is sent
  `ui/resource-teardown` and has the host's `teardownWait` to save what it must, with its frame still in the page.
  The element's scope does the same when it closes. Take the element out afterwards: removing it first ends the view
  unasked, because the browser destroys the frame along with the element.
- **A view is framed once.** When its mount ends (torn down, navigated, or its port closed) the frame goes, and the
  element shows how it ended. Putting it back does not frame it again; frame a new mount instead.

`view.state` is a `Squawk[ViewState]` of the view's life (`Detached`, `Starting`, `Serving`, `Asking`, `Leaving`,
`Ended`, `Removed`, `Failed`), and `view.until(p)` / `view.ended` wait on it.

## Theming

The chrome reads these custom properties, which reach into the shadow root:

| Property | Default |
| --- | --- |
| `--ascent-mcp-view-border` | `#c8c8d0` |
| `--ascent-mcp-view-radius` | `8px` |
| `--ascent-mcp-view-font` | `13px system-ui, sans-serif` |
| `--ascent-mcp-view-muted` | `#5a5a66` |
| `--ascent-mcp-view-question` | `#f6f6f9` |

## Demo and tests

`sbt mcpHostDemoJS/ascentPreview` serves [example/mcp-host](../example/mcp-host): a counter server running in the page,
an ascent-mcp-app counter view, and a live audit of every decision the host makes.

The suite runs in Firefox under `ChekhovJSEnv`, around a real heddle server reached in memory:
`sbt mcpHostJS/Test/testFull`. It is part of the e2e job, not `testJS`.
