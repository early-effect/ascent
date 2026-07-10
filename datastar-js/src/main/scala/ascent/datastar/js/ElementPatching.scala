package ascent.datastar.js

import ascent.datastar.{ElementPatchMode, RemoteEvent}
import ascent.dom

import scala.scalajs.js

/** Applies an incoming `datastar-patch-elements` event to the live DOM by CSS selector and mode.
  *
  * v1 implements the DIRECT modes via the browser's own HTML-parsing entry points (`innerHTML`, `insertAdjacentHTML`,
  * `outerHTML`, `remove`). A real idiomorph-equivalent morph that preserves focus/caret/scroll for `outer`/`inner`
  * lands in Phase 3b — until then those two modes replace wholesale (a focused field inside a patched region loses
  * focus; the signals path avoids this).
  *
  * `selector` is resolved with `querySelector`; when absent the protocol falls back to the element's own `id`, which
  * the incoming HTML must carry — v1 requires an explicit selector and logs-skips otherwise (full id-fallback comes
  * with the morph work).
  */
object ElementPatching:

  /** insertAdjacentHTML position tokens. */
  private val Afterbegin  = "afterbegin"
  private val Beforeend   = "beforeend"
  private val Beforebegin = "beforebegin"
  private val Afterend    = "afterend"

  def apply(patch: RemoteEvent.PatchElements): Unit =
    patch.selector match
      case None =>
        // id-fallback requires parsing the incoming HTML's root id; deferred to the morph phase.
        ascent.js.Diagnostics.report(
          ascent.js.Diagnostics.Violation.PatchMissingSelector,
          "datastar: patch-elements without a selector is not supported (an explicit selector is required)",
        )
        ()
      case Some(sel) =>
        // If the selector addresses a KNOWN server region by `#id`, the registry is authoritative:
        // a region that isn't Live must not be patched even if a stale DOM node lingers (it may be
        // mid-teardown). Only non-region selectors fall through to a plain querySelector.
        val regionId = if sel.startsWith("#") then Some(sel.drop(1)) else None
        regionId.map(id => (id, ascent.js.ServerRegionRegistry.status(id))) match
          case Some((id, status)) if status != ascent.js.ServerRegionRegistry.Status.Unknown =>
            ascent.js.ServerRegionRegistry.lookup(id) match
              // The registry stores container handles opaquely (`Any`) since it's node-type-agnostic; on the
              // JS backend the handle is the real dom.Element the mount engine created — cast at this boundary.
              case Some(el) => applyTo(el.asInstanceOf[dom.Element], patch)
              case None     => reportMissingTarget(sel, id, status)
          case _ =>
            val target = dom.document.querySelector(sel)
            if target == null || js.isUndefined(target) then
              reportMissingTarget(sel, regionId.getOrElse(sel), ascent.js.ServerRegionRegistry.Status.Unknown)
            else applyTo(target, patch)
        end match

  /** Report a patch whose target didn't resolve, with the precise reason from the region registry. */
  private def reportMissingTarget(selector: String, id: String, status: ascent.js.ServerRegionRegistry.Status): Unit =
    val msg = status match
      case ascent.js.ServerRegionRegistry.Status.Vanished =>
        s"datastar: patch-elements targeted server region `$id`, which has unmounted — the patch was dropped"
      case ascent.js.ServerRegionRegistry.Status.Unknown =>
        s"datastar: patch-elements selector `$selector` matched nothing"
      case ascent.js.ServerRegionRegistry.Status.Live =>
        s"datastar: patch-elements selector `$selector` matched nothing (region `$id` is registered but not in the DOM?)"
    ascent.js.Diagnostics.report(
      ascent.js.Diagnostics.Violation.PatchTargetMissing(id, status),
      msg,
    )
  end reportMissingTarget

  private def applyTo(target: dom.Element, patch: RemoteEvent.PatchElements): Unit =
    val dyn = target.asInstanceOf[js.Dynamic]
    patch.mode match
      // inner/outer MORPH (Phase 3b): reuse matching nodes so focus/caret/scroll survive.
      case ElementPatchMode.Inner => Morph.inner(target, patch.html)
      case ElementPatchMode.Outer => Morph.outer(target, patch.html)
      // replace is the explicit "don't morph, swap wholesale" mode.
      case ElementPatchMode.Replace => dyn.outerHTML = patch.html
      case ElementPatchMode.Append  => target.insertAdjacentHTML(Beforeend, patch.html)
      case ElementPatchMode.Prepend => target.insertAdjacentHTML(Afterbegin, patch.html)
      case ElementPatchMode.Before  => target.insertAdjacentHTML(Beforebegin, patch.html)
      case ElementPatchMode.After   => target.insertAdjacentHTML(Afterend, patch.html)
      case ElementPatchMode.Remove  => target.remove()
    end match
  end applyTo
end ElementPatching
