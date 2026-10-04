package ascent.html

import ascent.ast.{IdMode, UI}
import ascent.css.{StyleRegistry, StyleSink}
import ascent.domcore.{DocumentTypeOverrides, PlatformOpaque, Serialize}
import ascent.domcore.generated.{
  Document as DomDocument,
  Element,
  HTMLInputElement,
  HTMLSelectElement,
  HTMLTextAreaElement,
  Node,
}
import ascent.js.{InMemoryDomOps, Mount}
import zio.*

/** Renders an [[ascent.ast.UI]] AST to an HTML string for server-side rendering.
  *
  * There is no separate server walker: `render` MOUNTS `ui` into a disposable in-memory dom-core document using the
  * SAME cross-platform [[Mount]] engine the browser uses (via [[InMemoryDomOps]]), then serializes the built tree.
  * Server output is produced by the exact reconciler the client runs, so the two cannot drift.
  *
  * [[render]] mounts under a throwaway `div` and returns that div's `innerHTML`: a fragment, no doctype. That compact
  * string is the morph form. [[renderDocument]] mounts an `html`-rooted tree as the document element of a fresh
  * document that already has an HTML doctype, and serializes the document. [[renderDocumentPretty]] is the indented
  * twin. Pretty output is for humans; feeding it back through morph would turn the newlines into text nodes.
  *
  * Mount reads each `Squawk`'s current value, skips event handlers (no dispatch off a browser), and fires lifecycle
  * hooks against the in-memory element (usually inert for a server fragment). Reactive boundaries render their current
  * value; `When(false)`/`Empty` produce nothing; `ForEach` dedupes keys: all inherited from Mount, not re-implemented.
  *
  * Form-control STATE is reflected before serialization: Mount routes `value`/`checked` through DOM *properties*
  * (correct live-DOM semantics), but the datastar morph path diffs *attributes*, so [[reflectFormState]] copies each
  * input/textarea/select's live `value`/`checked`/`selected` into the corresponding content attribute on the throwaway
  * tree just before serialization. The dom-core model stays attribute/property-correct; the SSR-specific reflection is
  * confined here.
  */
object Html:

  /** The HTML string plus the CSS collected from every style primitive touched while building the AST. */
  final case class Page(html: String, css: String)

  /** Why [[renderDocument]] refused a tree. */
  enum Error:

    /** The mounted document's element children were not exactly one `html` element. `tags` is those children, in order.
      * Empty when nothing mounted.
      */
    case NotHtmlRoot(tags: List[String])

    /** [[StylePlacement.InlineInHead]] needs a `head` to receive the stylesheet. */
    case NoHead

  /** Render `ui` to an HTML string snapshot. Requires the environment `R` only because [[UI.Scoped]] builders may need
    * it; a fully-static `UI[Any]` renders as a plain `UIO[String]`. Compact `innerHTML` (no incidental whitespace): the
    * form morph/patch consume. A fragment: no doctype, no document shell.
    */
  def render[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, String] =
    // A per-render registry over the noop sink: SSR gathers CSS from its snapshot (see renderPage), not by
    // injecting <style> during the build. Fresh per call, so nothing leaks between renders.
    StyleRegistry.make(StyleSink.noop).flatMap(mountFragment(ui, idMode, _)(markup))

  /** Indented, human-readable snapshot via [[Serialize.pretty]]. For docs and debugging only: newlines would become
    * text nodes if fed back through morph/patch. Prefer [[render]] for production SSR.
    */
  def renderPretty[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, String] =
    StyleRegistry.make(StyleSink.noop).flatMap(mountFragment(ui, idMode, _)(prettyChildren))

  /** Render `ui` plus the CSS its tree references. Returns both so a server can inline a `<style>` or serve the CSS
    * separately. Fully isolated per render: the CSS is exactly the styles THIS `ui` touched. A concurrent render of a
    * different UI can't bleed in. Read from this render's own [[StyleRegistry]] after the build.
    */
  def renderPage[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, Page] =
    for
      registry <- StyleRegistry.make(StyleSink.noop)
      html     <- mountFragment(ui, idMode, registry)(markup)
      blocks   <- registry.snapshot
    yield Page(html, cssOf(blocks))

  /** Like [[renderPage]], but the HTML field is [[renderPretty]] output. */
  def renderPagePretty[R](ui: UI[R], idMode: IdMode = IdMode.HashWithRegistry): URIO[R, Page] =
    for
      registry <- StyleRegistry.make(StyleSink.noop)
      html     <- mountFragment(ui, idMode, registry)(prettyChildren)
      blocks   <- registry.snapshot
    yield Page(html, cssOf(blocks))

  /** Mount `ui` as the document element and serialize the document, doctype included.
    *
    * `ui` must mount as exactly one `html` element. Anything else fails with [[Error.NotHtmlRoot]]. `placement` is
    * required: [[StylePlacement.InlineInHead]] appends one `style` in `head` when the collected CSS is non-empty (and
    * fails with [[Error.NoHead]] when there is no `head`); [[StylePlacement.Aside]] leaves `head` alone. Both return
    * the CSS on [[Page]], so the caller can place it either way without a second walk.
    *
    * Compact markup. The morph form for a fragment is still [[render]]; pretty document markup is
    * [[renderDocumentPretty]].
    */
  def renderDocument[R](
      ui: UI[R],
      placement: StylePlacement,
      idMode: IdMode = IdMode.HashWithRegistry,
  ): ZIO[R, Error, Page] =
    renderDocumentWith(ui, placement, idMode, Serialize.compact)

  /** Indented twin of [[renderDocument]]. For humans. Not the morph form. */
  def renderDocumentPretty[R](
      ui: UI[R],
      placement: StylePlacement,
      idMode: IdMode = IdMode.HashWithRegistry,
  ): ZIO[R, Error, Page] =
    renderDocumentWith(ui, placement, idMode, Serialize.pretty)

  /** Mount `ui` into a throwaway `div` against `registry`, reflect form state, then `serialize` the div. The div itself
    * never appears in the output.
    */
  private def mountFragment[R, A](ui: UI[R], idMode: IdMode, registry: StyleRegistry)(
      serialize: Element => A
  ): URIO[R, A] =
    val (doc, ops0)                      = InMemoryDomOps.make()
    given DomOps: ascent.js.DomOps[Node] = ops0
    val root: Element                    = doc.createElement("div", "")
    // `DomOps` is invariant. The parent is typed as `Node` so `mount` uses the `DomOps[Node]` in scope.
    val parent: Node = root
    for
      _ <- Mount.mount(ui, parent, idMode).provideSomeLayer[R](ZLayer.succeed(registry))
      _ <- ZIO.succeed(reflectFormState(parent))
    yield serialize(root)
  end mountFragment

  private def renderDocumentWith[R](
      ui: UI[R],
      placement: StylePlacement,
      idMode: IdMode,
      serialize: DomDocument => String,
  ): ZIO[R, Error, Page] =
    StyleRegistry.make(StyleSink.noop).flatMap { registry =>
      val (doc, ops0)                      = InMemoryDomOps.make()
      given DomOps: ascent.js.DomOps[Node] = ops0
      // The doctype is a real child, appended before mount, so the html element is the next child and
      // serialization reads it off the tree. A fragment document never gets one.
      doc.appendChild(DocumentTypeOverrides.html)
      val parent: Node = doc
      for
        _      <- Mount.mount(ui, parent, idMode).provideSomeLayer[R](ZLayer.succeed(registry))
        _      <- ZIO.succeed(reflectFormState(parent))
        _      <- requireHtmlRoot(doc)
        blocks <- registry.snapshot
        css = cssOf(blocks)
        _ <- placeStyles(doc, placement, css)
      yield Page(serialize(doc), css)
    }

  private def requireHtmlRoot(doc: DomDocument): IO[Error, Unit] =
    elementChildren(doc).map(_.tagName) match
      case List("html") => ZIO.unit
      case tags         => ZIO.fail(Error.NotHtmlRoot(tags))

  /** `InlineInHead` appends one `style` after the caller's head nodes when `css` is non-empty. The element is created
    * after mount, so it carries no `data-ascent` stamp. Its text is raw text: a `</style` in the CSS is spelled
    * `<\/style`.
    */
  private def placeStyles(doc: DomDocument, placement: StylePlacement, css: String): IO[Error, Unit] =
    placement match
      case StylePlacement.Aside        => ZIO.unit
      case StylePlacement.InlineInHead =>
        headOf(doc) match
          case None       => ZIO.fail(Error.NoHead)
          case Some(head) =>
            ZIO.succeed {
              if css.nonEmpty then
                val style = doc.createElement("style", "")
                style.appendChild(doc.createTextNode(css))
                head.appendChild(style)
                ()
            }

  private def headOf(doc: DomDocument): Option[Element] =
    elementChildren(doc)
      .find(_.tagName == "html")
      .flatMap(html => elementChildren(html).find(_.tagName == "head"))

  private def elementChildren(node: Node): List[Element] =
    val kids = node.childNodes
    (0 until kids.length).toList.flatMap(idx => Option(kids.item(idx)).collect { case e: Element => e })

  private def cssOf(blocks: Vector[(String, String)]): String =
    blocks.map(_._2).mkString("\n")

  private def markup(el: Element): String =
    el.innerHTML match
      case s: String         => s
      case _: PlatformOpaque => ""

  private def prettyChildren(el: Element): String =
    val kids = el.childNodes
    (0 until kids.length).toList.flatMap(idx => Option(kids.item(idx))).map(Serialize.pretty).mkString("\n")

  /** Reflect live form-control properties into content attributes across the mounted tree, so the serialized markup
    * carries current form state for the datastar morph (which diffs attributes). `value` → `value` attr on
    * input/textarea/select; `checked` → presence attr on input; `selected` → presence attr on option. Walks every
    * child, so a document (not itself an element) still reaches inputs inside it. Idempotent and confined to the
    * throwaway SSR tree.
    */
  private def reflectFormState(node: Node): Unit =
    // Reflect a `value` only when it's actually set (non-empty): an input the author gave no value has
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
    val kids = node.childNodes
    (0 until kids.length).foreach { idx =>
      Option(kids.item(idx)).foreach(reflectFormState)
    }
  end reflectFormState
end Html
