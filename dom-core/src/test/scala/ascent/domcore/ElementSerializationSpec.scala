package ascent.domcore

import ascent.domcore.generated.{Document, Element, HTMLFormElement, HTMLInputElement, HTMLMetaElement}
import zio.test.*

/** `innerHTML` / `outerHTML` on the in-memory [[Element]] and the escaping in [[HtmlSerialize]], pinning the compact
  * WHATWG fragment-serialization shape the SSR/morph path depends on.
  */
object ElementSerializationSpec extends ZIOSpecDefault:

  private def doc: Document = ascent.domcore.generated.DocumentMemory()

  def spec = suite("Element serialization (innerHTML / outerHTML)")(
    suite("HtmlSerialize.escapeText")(
      test("escapes the markup-significant trio, not quotes") {
        assertTrue(HtmlSerialize.escapeText("""a & b < c > d " e ' f""") == """a &amp; b &lt; c &gt; d " e ' f""")
      },
      test("escapes & before producing entities (no double-escape)") {
        assertTrue(HtmlSerialize.escapeText("&lt;") == "&amp;lt;")
      },
      test("a crafted closing tag is neutralised") {
        assertTrue(HtmlSerialize.escapeText("</script>") == "&lt;/script&gt;")
      },
    ),
    suite("HtmlSerialize.escapeAttr")(
      test("escapes both quote characters plus the trio") {
        assertTrue(HtmlSerialize.escapeAttr("""x" onmouseover="alert(1)""") == """x&quot; onmouseover=&quot;alert(1)""")
      },
      test("escapes the single quote as &#x27;") {
        assertTrue(HtmlSerialize.escapeAttr("it's") == "it&#x27;s")
      },
    ),
    suite("outerHTML")(
      test("an empty non-void element self-closes with a separate end tag") {
        val el = doc.createElement("div", "")
        assertTrue(el.outerHTML == "<div></div>")
      },
      test("a void element emits no end tag and no children") {
        val el = doc.createElement("br", "")
        assertTrue(el.outerHTML == "<br>")
      },
      test("attributes emit in insertion order, each double-quoted and escaped") {
        val el = doc.createElement("div", "")
        el.setAttribute("id", "main")
        el.setAttribute("title", "a\"b")
        assertTrue(el.outerHTML == """<div id="main" title="a&quot;b"></div>""")
      },
      test("a presence attribute stored as empty string emits name=\"\"") {
        val el = doc.createElement("input", "")
        el.setAttribute("disabled", "")
        assertTrue(el.outerHTML == """<input disabled="">""")
      },
      test("text children are escaped; element children nest") {
        val el = doc.createElement("p", "")
        el.appendChild(doc.createTextNode("a < b"))
        val span = doc.createElement("span", "")
        span.appendChild(doc.createTextNode("x"))
        el.appendChild(span)
        assertTrue(el.outerHTML == "<p>a &lt; b<span>x</span></p>")
      },
      test("a comment child serializes as <!--data-->") {
        val el = doc.createElement("div", "")
        el.appendChild(doc.createComment("note"))
        assertTrue(el.outerHTML == "<div><!--note--></div>")
      },
      test("compact: no incidental whitespace between children") {
        val ul = doc.createElement("ul", "")
        ul.appendChild(doc.createElement("li", ""))
        ul.appendChild(doc.createElement("li", ""))
        assertTrue(ul.outerHTML == "<ul><li></li><li></li></ul>")
      },
    ),
    suite("innerHTML")(
      test("serializes only the children, not the element itself") {
        val el = doc.createElement("div", "")
        el.appendChild(doc.createTextNode("hi"))
        val b = doc.createElement("b", "")
        b.appendChild(doc.createTextNode("x"))
        el.appendChild(b)
        assertTrue(el.innerHTML == "hi<b>x</b>")
      },
      test("empty element has empty innerHTML") {
        assertTrue(doc.createElement("div", "").innerHTML == "")
      },
    ),
    suite("Serialize.compact / pretty")(
      test("compact equals outerHTML (the canonical machine form)") {
        val el = doc.createElement("div", "")
        el.setAttribute("id", "x")
        el.appendChild(doc.createTextNode("hi"))
        assertTrue(Serialize.compact(el) == el.outerHTML)
      },
      test("pretty keeps a single-text-child element inline") {
        val el = doc.createElement("span", "")
        el.appendChild(doc.createTextNode("hi"))
        assertTrue(Serialize.pretty(el) == "<span>hi</span>")
      },
      test("pretty indents element children one per line, 2 spaces per level") {
        val ul = doc.createElement("ul", "")
        val a  = doc.createElement("li", "")
        a.appendChild(doc.createTextNode("a"))
        val b = doc.createElement("li", "")
        b.appendChild(doc.createTextNode("b"))
        ul.appendChild(a); ul.appendChild(b)
        assertTrue(Serialize.pretty(ul) == "<ul>\n  <li>a</li>\n  <li>b</li>\n</ul>")
      },
      test("pretty renders a void element on one line") {
        assertTrue(Serialize.pretty(doc.createElement("br", "")) == "<br>")
      },
      test("pretty escapes text just like compact") {
        val p = doc.createElement("p", "")
        p.appendChild(doc.createTextNode("a < b"))
        assertTrue(Serialize.pretty(p) == "<p>a &lt; b</p>")
      },
    ),
    suite("reflected content-attribute names")(
      test("meta httpEquiv and content serialize as http-equiv and content") {
        doc.createElement("meta", "") match
          case meta: HTMLMetaElement =>
            meta.httpEquiv = "refresh"
            meta.content = "0"
            val html = Serialize.compact(meta)
            assertTrue(html.contains("""http-equiv="refresh""""), html.contains("""content="0""""))
          case other =>
            assertTrue(other.isInstanceOf[HTMLMetaElement])
      },
      test("form acceptCharset serializes as accept-charset") {
        doc.createElement("form", "") match
          case form: HTMLFormElement =>
            form.acceptCharset = "utf-8"
            assertTrue(Serialize.compact(form) == """<form accept-charset="utf-8"></form>""")
          case other =>
            assertTrue(other.isInstanceOf[HTMLFormElement])
      },
      test("defaultChecked writes checked; the checked property does not") {
        val withDefault = doc.createElement("input", "") match
          case input: HTMLInputElement =>
            input.defaultChecked = true
            Serialize.compact(input)
          case other =>
            other.tagName
        val withProperty = doc.createElement("input", "") match
          case input: HTMLInputElement =>
            input.checked = true
            Serialize.compact(input)
          case other =>
            other.tagName
        assertTrue(withDefault == """<input checked="">""", withProperty == "<input>")
      },
    ),
    suite("Document serialization")(
      test("a plain document has no doctype") {
        assertTrue(doc.doctype == null)
      },
      test("an HTML doctype plus an html child starts with <!DOCTYPE html><html") {
        val page = ascent.domcore.generated.DocumentMemory()
        page.appendChild(DocumentTypeOverrides.html)
        page.appendChild(page.createElement("html", ""))
        val name = page.doctype match
          case null => ""
          case dt   => dt.name
        val compact = Serialize.compact(page)
        val pretty  = Serialize.pretty(page)
        assertTrue(
          name == "html",
          compact.startsWith("<!DOCTYPE html><html"),
          pretty.startsWith("<!DOCTYPE html>\n<html"),
        )
      },
      test("an element and no doctype has no <!DOCTYPE") {
        val page = ascent.domcore.generated.DocumentMemory()
        page.appendChild(page.createElement("div", ""))
        val compact = Serialize.compact(page)
        assertTrue(compact == "<div></div>", !compact.contains("<!DOCTYPE"))
      },
    ),
    suite("raw text")(
      test("a script keeps a literal < and spells </script as <\\/script") {
        val script = doc.createElement("script", "")
        script.appendChild(doc.createTextNode("if (a < b) x;</SCRIPT>"))
        assertTrue(Serialize.compact(script) == "<script>if (a < b) x;<\\/SCRIPT></script>")
      },
      test("a style spells </style as <\\/style and keeps a literal <") {
        val style = doc.createElement("style", "")
        style.appendChild(doc.createTextNode("a < b;</StYlE>"))
        assertTrue(Serialize.compact(style) == "<style>a < b;<\\/StYlE></style>")
      },
      test("a paragraph still escapes <") {
        val p = doc.createElement("p", "")
        p.appendChild(doc.createTextNode("a < b"))
        assertTrue(Serialize.compact(p) == "<p>a &lt; b</p>")
      },
      test("pretty script keeps a literal <") {
        val script = doc.createElement("script", "")
        script.appendChild(doc.createTextNode("a < b"))
        assertTrue(Serialize.pretty(script) == "<script>a < b</script>")
      },
    ),
  )
end ElementSerializationSpec
