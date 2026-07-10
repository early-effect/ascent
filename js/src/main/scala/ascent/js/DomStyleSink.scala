package ascent.js

import ascent.css.StyleSink
import ascent.dom
import zio.*

import scala.scalajs.js

/** A [[StyleSink]] that mounts CSS rule blocks as `<style>` elements in `document.head`.
  *
  * Each block is keyed by the [[ascent.css.CssClass]]'s auto-derived class name (the `key` argument). Re-appending the
  * same key replaces the existing `<style>`'s text content **without** detaching the element — keeping its identity
  * stable for the browser's style recalc machinery and for any DOM observers that might watch `<head>`.
  *
  * This is the only place the css module's authoring API meets the actual DOM. JVM/Native users can author the same
  * `CssClass` values; they just plug in [[StyleSink.noop]] (or a future SSR string-collector sink) instead.
  */
object DomStyleSink extends StyleSink:

  /** CSS attribute we tag injected `<style>` elements with so we can find / replace them. */
  private val markerAttr: String = "data-ascent-class"

  def append(key: String, css: String): UIO[Unit] = ZIO.succeed(appendSync(key, css))

  /** Synchronous core of [[append]] — for callers already inside a synchronous context. */
  private[ascent] def appendSync(key: String, css: String): Unit =
    val head     = dom.document.asInstanceOf[js.Dynamic].head
    val existing = head.querySelector(selectorFor(key))
    if existing == null || js.isUndefined(existing) then
      val style = dom.document.createElement("style")
      style.setAttribute(markerAttr, key)
      style.asInstanceOf[js.Dynamic].textContent = css
      head.appendChild(style)
    else
      // Same DOM node, just rewrite its body. Identity preserved.
      existing.asInstanceOf[js.Dynamic].textContent = css
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
