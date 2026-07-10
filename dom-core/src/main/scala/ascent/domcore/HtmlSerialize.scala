package ascent.domcore

/** Pure HTML text/attribute escaping for the in-memory DOM's [[ascent.domcore.generated.Element.outerHTML]] /
  * `innerHTML` serialization. Neutral (no ascent-app policy) and total, so it's unit-testable in isolation.
  *
  * Escaping rules match the WHATWG "HTML fragment serialization algorithm":
  *   - text content escapes `&`, `<`, `>` (NOT quotes — quotes are literal in text);
  *   - attribute values escape `&` and `"` (a double-quoted attribute only needs those); we ALSO escape `<`/`>`/`'`
  *     defensively so the output is safe regardless of how a consumer re-parses it (matching the prior renderer's
  *     `HtmlEncoding.escapeAttr`, which the SSR morph path and its tests depend on).
  *   - `&` is replaced FIRST so we never double-escape an entity we just produced.
  */
object HtmlSerialize:

  /** Escape text-node content: the markup-significant trio `& < >`. Quotes are left literal (they're not special in
    * text position).
    */
  def escapeText(s: String): String =
    val sb = StringBuilder(s.length)
    var i  = 0
    while i < s.length do
      s.charAt(i) match
        case '&' => sb.append("&amp;")
        case '<' => sb.append("&lt;")
        case '>' => sb.append("&gt;")
        case c   => sb.append(c)
      i += 1
    sb.toString
  end escapeText

  /** Escape an attribute value: the trio plus both quote characters (double as `&quot;`, single as `&#x27;`) so the
    * value is safe in either quoting style.
    */
  def escapeAttr(s: String): String =
    val sb = StringBuilder(s.length)
    var i  = 0
    while i < s.length do
      s.charAt(i) match
        case '&'  => sb.append("&amp;")
        case '<'  => sb.append("&lt;")
        case '>'  => sb.append("&gt;")
        case '"'  => sb.append("&quot;")
        case '\'' => sb.append("&#x27;")
        case c    => sb.append(c)
      i += 1
    sb.toString
  end escapeAttr
end HtmlSerialize
