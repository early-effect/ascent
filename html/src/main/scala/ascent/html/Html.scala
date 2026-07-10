package ascent.html

import ascent.ast.{IdMode, UI}
import ascent.css.{StyleRegistry, StyleSink}
import ascent.domcore.generated.{Element, HTMLInputElement, HTMLSelectElement, HTMLTextAreaElement, Node}
import ascent.js.{InMemoryDomOps, Mount}
import zio.*

/** Renders an [[ascent.ast.UI]] AST to an HTML string for server-side rendering.
  *
  * There is no separate server walker: `render` MOUNTS `ui` into a disposable in-memory dom-core [[Document]] using the
  * SAME cross-platform [[Mount]] engine the browser uses (via [[InMemoryDomOps]]), then serializes the built tree with
  * `root.innerHTML`. Server output is produced by the exact reconciler the client runs, so the two cannot drift.
  *
  * Mount reads each `Squawk`'s current value, skips event handlers (no dispatch off a browser), and fires lifecycle
  * hooks against the in-memory element (usually inert for a server fragment). Reactive boundaries render their current
  * value; `When(false)`/`Empty` produce nothing; `ForEach` dedupes keys — all inherited from Mount, not re-implemented.
  *
  * Form-control STATE is reflected before serialization: Mount routes `value`/`checked` through DOM *properties*
  * (correct live-DOM semantics), but the datastar morph path diffs *attributes*, so [[reflectFormState]] copies each
  * input/textarea/select's live `value`/`checked`/`selected` into the corresponding content attribute on the throwaway
  * tree just before `innerHTML`. The dom-core model stays attribute/property-correct; the SSR-specific reflection is
  * confined here.
  */
object Html:

  /** The HTML string plus the CSS collected from every style primitive touched while building the AST. */
  final case class Page(html: String, css: String)

  /** Render `ui` to an HTML string snapshot. Requires the environment `R` only because [[UI.Scoped]] builders may need
    * it; a fully-static `UI[Any]` renders as a plain `UIO[String]`.
    */
  def render[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, String] =
    // A per-render registry over the noop sink: SSR gathers CSS from its snapshot (see renderPage), not by
    // injecting <style> during the build. Fresh per call, so nothing leaks between renders.
    StyleRegistry.make(StyleSink.noop).flatMap(mountToString(ui, idMode, _))

  /** Render `ui` plus the CSS its tree references. Returns both so a server can inline a `<style>` or serve the CSS
    * separately. Fully isolated per render: the CSS is exactly the styles THIS `ui` touched — a concurrent render of a
    * different UI can't bleed in — read from this render's own [[StyleRegistry]] after the build.
    */
  def renderPage[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, Page] =
    for
      registry <- StyleRegistry.make(StyleSink.noop)
      html     <- mountToString(ui, idMode, registry)
      blocks   <- registry.snapshot
    yield Page(html, blocks.map(_._2).mkString("\n"))

  /** Mount `ui` into a throwaway in-memory tree against `registry`, reflect form state, and serialize `root.innerHTML`.
    */
  private def mountToString[R](ui: UI[R], idMode: IdMode, registry: StyleRegistry): URIO[R, String] =
    val (doc, ops0)                      = InMemoryDomOps.make()
    given DomOps: ascent.js.DomOps[Node] = ops0
    // A disposable root to mount into; we serialize its children (root.innerHTML), so the root wrapper
    // itself never appears in the output — just `ui`'s own markup.
    val root: Node = doc.createElement("div", "")
    for
      _ <- Mount.mount(ui, root, idMode).provideSomeLayer[R](ZLayer.succeed(registry))
      _ <- ZIO.succeed(reflectFormState(root))
    yield root.asInstanceOf[Element].innerHTML.asInstanceOf[String]
  end mountToString

  /** Reflect live form-control properties into content attributes across the mounted tree, so the serialized markup
    * carries current form state for the datastar morph (which diffs attributes). `value` → `value` attr on
    * input/textarea/select; `checked` → presence attr on input; `selected` → presence attr on option. Walks the whole
    * subtree once. Idempotent and confined to the throwaway SSR tree — never touches a live browser DOM.
    */
  private def reflectFormState(node: Node): Unit =
    // Reflect a `value` only when it's actually set (non-empty) — an input the author gave no value has
    // `value == ""`, and emitting `value=""` for it would add an attribute the source AST never had (and
    // break byte-exact SSR output). `checked` reflects only when true (a present boolean attribute).
    node match
      case i: HTMLInputElement =>
        if i.value.nonEmpty then i.setAttribute("value", i.value)
        if i.checked then i.setAttribute("checked", "")
      case t: HTMLTextAreaElement =>
        if t.value.nonEmpty then t.setAttribute("value", t.value)
      case s: HTMLSelectElement =>
        if s.value.nonEmpty then s.setAttribute("value", s.value)
      case _ => ()
    // Recurse into children (every node kind that has them is a NodeMemoryBase-backed Element/Document).
    node match
      case e: Element =>
        val kids = e.children
        (0 until kids.length).foreach(idx => reflectFormState(kids.item(idx)))
      case _ => ()
  end reflectFormState
end Html
