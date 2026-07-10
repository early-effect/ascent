package ascent.js

import ascent.ast.{IdMode, UI}
import ascent.css.StyleRegistry
import ascent.dom
import zio.*

/** The browser entry point: mount a [[UI]] into the real DOM.
  *
  * The cross-platform [[Mount]] engine (in `ascent-mount-engine`) is generic over a node type `N` with a
  * `given DomOps[N]` and requires a per-render [[StyleRegistry]] in its environment. On the browser the node type is
  * always [[JsDomOps]]'s `dom.Node` and the style sink is always [[DomStyleSink]] (which injects `<style>` into
  * `<head>`). This object binds both once — and provides a FRESH scoped `StyleRegistry` over `DomStyleSink` per mount —
  * so app code (and the js test suite) calls `AscentApp.mount(ui, parent)` / `AscentApp.mountBody(ui)` with no
  * ceremony, and two mounts on one page never share a style catalog (they both dedup into the same `<head>` by key).
  */
object AscentApp:

  import JsDomOps.given

  /** Mount `ui` as the new content of `parent` (a real browser element), returning the [[Subscriptions]] to run at
    * unmount. A fresh [[StyleRegistry]] over [[DomStyleSink]] collects this render's CSS, flushing each block into
    * `<head>` as the tree builds so nodes mount already styled.
    */
  def mount[R](ui: UI[R], parent: dom.Element, idMode: IdMode = IdMode.HashWithRegistry): URIO[R, Subscriptions] =
    Mount.mount[R, dom.Node](ui, parent, idMode).provideSomeLayer[R](StyleRegistry.scoped(DomStyleSink))

  /** Mount `ui` as the document `<body>`, replacing the placeholder body the HTML shipped with. `ui`'s root should be
    * an `E.body(...)`. See [[Mount.mountBody]] for the lifecycle contract.
    */
  def mountBody[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, Subscriptions] =
    Mount.mountBody[R, dom.Node](ui, idMode).provideSomeLayer[R](StyleRegistry.scoped(DomStyleSink))

end AscentApp
