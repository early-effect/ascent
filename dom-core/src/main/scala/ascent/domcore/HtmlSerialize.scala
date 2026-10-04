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

  /** Parents whose text is not HTML-escaped: `script`, `style`, and the historic raw-text elements. */
  private val rawTextTags: Set[String] =
    Set("script", "style", "xmp", "iframe", "noembed", "noframes", "plaintext")

  /** Text-node data. Ordinary parents use [[escapeText]]. A raw-text parent emits the data literally, except a
    * case-insensitive `</` + that parent's own tag, which is spelled `<\/` + the original spelling (`</SCRIPT` in a
    * script becomes `<\/SCRIPT`). That keeps the element from ending early, and in CSS `\/` is still `/`.
    */
  def textContent(data: String, parentTag: Option[String]): String =
    parentTag match
      case Some(tag) if rawTextTags.contains(tag.toLowerCase) => escapeRawText(data, tag)
      case _                                                  => escapeText(data)

  /** Rewrite `</` + `tag` (ASCII case-insensitive) to `<\/` + the matched spelling. Every other character stays. */
  def escapeRawText(data: String, tag: String): String =
    val needle = tag.toLowerCase
    val n      = needle.length
    val sb     = StringBuilder(data.length)
    var i      = 0
    while i < data.length do
      if closesRawTag(data, i, needle) then
        sb.append("<\\/")
        val start = i + 2
        sb.append(data.substring(start, start + n))
        i = start + n
      else
        sb.append(data.charAt(i))
        i += 1
    sb.toString
  end escapeRawText

  private def closesRawTag(data: String, i: Int, needle: String): Boolean =
    val n = needle.length
    i + 2 + n <= data.length &&
    data.charAt(i) == '<' &&
    data.charAt(i + 1) == '/' &&
    equalsIgnoreAscii(data, i + 2, needle)

  private def equalsIgnoreAscii(data: String, offset: Int, needle: String): Boolean =
    var k  = 0
    var ok = true
    while k < needle.length && ok do
      ok = foldAscii(data.charAt(offset + k)) == needle.charAt(k)
      k += 1
    ok

  private def foldAscii(c: Char): Char =
    if c >= 'A' && c <= 'Z' then (c + 32).toChar else c

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
