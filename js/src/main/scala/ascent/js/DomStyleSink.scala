package ascent.js

import ascent.css.StyleSink
import ascent.dom
import zio.*

/** Where a render's `<style>` blocks go. The document's `<head>` styles the document; a shadow root sees none of it, so
  * what renders inside one needs its styles there.
  */
enum StyleTarget:
  case Head
  case Shadow(root: dom.ShadowRoot)

object StyleTarget:
  /** What styles `node`: the shadow root it is inside, or the document head. */
  def of(node: dom.Node): StyleTarget =
    node.getRootNode() match
      case root: dom.ShadowRoot => Shadow(root)
      case _                    => Head

/** A [[StyleSink]] that mounts CSS rule blocks as `<style>` elements in `document.head`; [[DomStyleSink.into]] writes
  * them to any [[StyleTarget]].
  *
  * Each block is keyed by the [[ascent.css.CssClass]]'s auto-derived class name (the `key` argument). Re-appending the
  * same key replaces the existing `<style>`'s text content **without** detaching the element — keeping its identity
  * stable for the browser's style recalc machinery and for any DOM observers that might watch it.
  *
  * This is the only place the css module's authoring API meets the actual DOM. JVM/Native users can author the same
  * `CssClass` values; they just plug in [[StyleSink.noop]] (or a future SSR string-collector sink) instead.
  */
object DomStyleSink extends StyleSink:

  /** CSS attribute we tag injected `<style>` elements with so we can find / replace them. */
  private val markerAttr: String = "data-ascent-class"

  def append(key: String, css: String): UIO[Unit] = ZIO.succeed(appendSync(StyleTarget.Head, key, css))

  /** A sink whose blocks go to `target`, deduplicated there by key. */
  def into(target: StyleTarget): StyleSink = new StyleSink:
    def append(key: String, css: String): UIO[Unit] = ZIO.succeed(appendSync(target, key, css))

  /** Synchronous core of [[append]] — for callers already inside a synchronous context. */
  private[ascent] def appendSync(target: StyleTarget, key: String, css: String): Unit =
    val selector = selectorFor(key)
    val existing = target match
      case StyleTarget.Head         => Option(dom.document.head.querySelector(selector))
      case StyleTarget.Shadow(root) => Option(root.querySelector(selector))
    existing match
      // Same DOM node, just rewrite its body. Identity preserved.
      case Some(style) => style.textContent = css
      case None        =>
        val style = dom.document.createElement("style")
        style.setAttribute(markerAttr, key)
        style.textContent = css
        val _ = target match
          case StyleTarget.Head         => dom.document.head.appendChild(style)
          case StyleTarget.Shadow(root) => root.appendChild(style)
    end match
  end appendSync

  private def selectorFor(key: String): String =
    s"""style[$markerAttr="${escapeAttrValue(key)}"]"""

  /** Minimal CSS attribute-value escaping — backslashes and double-quotes only. The keys we pass come from
    * [[ascent.css.CssClass.deriveClassName]] which is already CSS-safe, so this is a defense-in-depth measure for any
    * future caller using a custom key.
    */
  private def escapeAttrValue(s: String): String =
    s.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case c    => c.toString
    }
end DomStyleSink
