package ascent.js

import zio.test.*

/** Pure namespace selection for Mount — no DOM. Guards the SVG/`style`/foreignObject rules the JS backend relies on
  * when choosing `createElement` vs `createElementNS`.
  */
object DomOpsNamespaceSpec extends ZIOSpecDefault:

  def spec = suite("DomOps namespace helpers")(
    test("svg and math open their namespaces from an HTML parent") {
      assertTrue(
        DomOps.namespaceFor(DomOps.HtmlNs, "svg") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.HtmlNs, "SVG") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.HtmlNs, "math") == DomOps.MathNs,
        DomOps.namespaceFor(DomOps.HtmlNs, "div") == DomOps.HtmlNs,
        DomOps.namespaceFor(DomOps.HtmlNs, "style") == DomOps.HtmlNs,
      )
    },
    test("descendants of svg inherit SVG — including ambiguous tags like style and a") {
      assertTrue(
        DomOps.namespaceFor(DomOps.SvgNs, "g") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.SvgNs, "text") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.SvgNs, "style") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.SvgNs, "a") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.SvgNs, "title") == DomOps.SvgNs,
      )
    },
    test("foreignObject itself is SVG; its children switch to HTML") {
      assertTrue(
        DomOps.namespaceFor(DomOps.SvgNs, "foreignObject") == DomOps.SvgNs,
        DomOps.childrenNamespace(DomOps.SvgNs, "foreignObject") == DomOps.HtmlNs,
        DomOps.childrenNamespace(DomOps.SvgNs, "g") == DomOps.SvgNs,
        DomOps.namespaceFor(DomOps.HtmlNs, "div") == DomOps.HtmlNs,
      )
    },
    test("nested svg under HTML opens SVG; nested svg under MathML stays Math until svg tag") {
      assertTrue(
        DomOps.namespaceFor(DomOps.MathNs, "mrow") == DomOps.MathNs,
        DomOps.namespaceFor(DomOps.MathNs, "svg") == DomOps.SvgNs,
      )
    },
  )
end DomOpsNamespaceSpec
