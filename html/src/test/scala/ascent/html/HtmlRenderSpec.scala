package ascent.html

import ascent.ast.{AstId, Attr, IdMode, UI}
import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

/** Renderer behaviour across every AST node. `stripIds` drops the `data-ascent="…"` structural-hash stamp so assertions
  * can pin markup, except for the one test that checks the stamp itself.
  */
object HtmlRenderSpec extends ZIOSpecDefault:

  private val stamp                          = """ data-ascent="[^"]*"""".r
  private def stripIds(html: String): String = stamp.replaceAllIn(html, "")

  private def el(tag: String, attrs: Vector[Attr[Any]] = Vector.empty, children: Vector[UI[Any]] = Vector.empty) =
    UI.Element[Any](tag, attrs, children)

  def spec = suite("Html.render")(
    test("a static element with text child round-trips, ids stripped") {
      for html <- Html.render(el("div", children = Vector(UI.Text("hi"))))
      yield assertTrue(stripIds(html) == "<div>hi</div>")
    },
    test("text content is escaped") {
      for html <- Html.render(el("p", children = Vector(UI.Text("a < b & c"))))
      yield assertTrue(stripIds(html) == "<p>a &lt; b &amp; c</p>")
    },
    test("a void element self-closes with no end tag and no children") {
      for html <- Html.render(el("br"))
      yield assertTrue(stripIds(html) == "<br>")
    },
    test("a void element renders its attributes") {
      for html <- Html.render(el("input", attrs = Vector(Attr.StaticAttr("type", AttrValue.Str("text")))))
      yield assertTrue(stripIds(html) == """<input type="text">""")
    },
    test("a non-void element always gets a closing tag even when empty") {
      for html <- Html.render(el("div"))
      yield assertTrue(stripIds(html) == "<div></div>")
    },
    test("boolean-present attr renders, absent/false attrs are omitted") {
      val attrs = Vector(
        Attr.StaticAttr("checked", AttrValue.Bool(true)),
        Attr.StaticAttr("disabled", AttrValue.Bool(false)),
        Attr.StaticAttr("data-x", AttrValue.Absent),
      )
      for html <- Html.render(el("input", attrs = attrs))
      yield assertTrue(stripIds(html) == """<input checked="">""")
    },
    test("an input's set `value` is reflected into the markup as a `value` attribute (SSR morph contract)") {
      // Mount routes `value` through the DOM property, but SSR must emit it as an attribute since the datastar morph
      // diffs attributes. Only a non-empty value is emitted (the plain `<input type="text">` case has no value="").
      for html <- Html.render(el("input", attrs = Vector(Attr.StaticAttr("value", AttrValue.Str("typed")))))
      yield assertTrue(stripIds(html) == """<input value="typed">""")
    },
    test("multiple class attrs compose into one deduped token list in source order") {
      val attrs = Vector(
        Attr.StaticAttr("class", AttrValue.Str("a b")),
        Attr.StaticAttr("class", AttrValue.Str("b c")),
      )
      for html <- Html.render(el("div", attrs = attrs))
      yield assertTrue(stripIds(html) == """<div class="a b c"></div>""")
    },
    test("attribute values are escaped (injection neutralised)") {
      val attrs = Vector(Attr.StaticAttr("title", AttrValue.Str("""x" onmouseover="alert(1)""")))
      for html <- Html.render(el("div", attrs = attrs))
      yield assertTrue(stripIds(html).contains("""title="x&quot; onmouseover=&quot;alert(1)""""))
    },
    test("Empty renders nothing") {
      for html <- Html.render(UI.Empty)
      yield assertTrue(html == "")
    },
    test("Fragment renders its children in order with no wrapper") {
      val frag = UI.Fragment[Any](Vector(UI.Text("a"), el("span", children = Vector(UI.Text("b")))))
      for html <- Html.render(frag)
      yield assertTrue(stripIds(html) == "a<span>b</span>")
    },
    test("ReactiveText renders the squawk's current value") {
      for
        s    <- sq("now")
        html <- Html.render(UI.ReactiveText(s))
      yield assertTrue(html == "now")
    },
    test("ReactiveChild renders the current subtree") {
      for
        s    <- sq[UI[Any]](el("span", children = Vector(UI.Text("x"))))
        html <- Html.render(UI.ReactiveChild(s))
      yield assertTrue(stripIds(html) == "<span>x</span>")
    },
    test("When(true) renders the body; When(false) renders nothing") {
      for
        t       <- sq(true)
        f       <- sq(false)
        yesHtml <- Html.render(UI.When(t, () => el("b", children = Vector(UI.Text("yes")))))
        noHtml  <- Html.render(UI.When(f, () => el("b", children = Vector(UI.Text("yes")))))
      yield assertTrue(stripIds(yesHtml) == "<b>yes</b>", noHtml == "")
    },
    test("a reactive attribute renders its current value") {
      for
        s    <- sq[AttrValue](AttrValue.Str("v1"))
        html <- Html.render(el("div", attrs = Vector(Attr.ReactiveAttr("data-k", s))))
      yield assertTrue(stripIds(html) == """<div data-k="v1"></div>""")
    },
    suite("ForEach")(
      test("renders one row per item") {
        for
          items <- sq[Seq[String]](Seq("a", "b"))
          html  <- Html.render(UI.ForEach[String, Any](items, identity, s => el("li", children = Vector(UI.Text(s)))))
        yield assertTrue(stripIds(html) == "<li>a</li><li>b</li>")
      },
      test("dedupes by key, first occurrence wins (like Mount)") {
        for
          items <- sq[Seq[String]](Seq("a", "a", "b"))
          html  <- Html.render(UI.ForEach[String, Any](items, identity, s => el("li", children = Vector(UI.Text(s)))))
        yield assertTrue(stripIds(html) == "<li>a</li><li>b</li>")
      },
    ),
    test("event handlers and lifecycle hooks never appear in the output") {
      val attrs = Vector[Attr[Any]](
        Attr.StaticAttr("id", AttrValue.Str("btn")),
        Attr.EventHandler[Any]("click", _ => ZIO.unit),
        Attr.OnMount[Any](_ => ZIO.unit),
        Attr.OnUnmount[Any](_ => ZIO.unit),
        Attr.OnMountScoped[Any](_ => ZIO.unit),
      )
      for html <- Html.render(el("button", attrs = attrs, children = Vector(UI.Text("go"))))
      yield assertTrue(
        stripIds(html) == """<button id="btn">go</button>""",
        !html.contains("click"),
        !html.contains("onmount"),
      )
    },
    test("Scoped runs its builder once and renders the result") {
      for
        ran <- Ref.make(0)
        scoped = UI.Scoped[Any](ran.update(_ + 1).as(el("div", children = Vector(UI.Text("built")))))
        html  <- Html.render(scoped)
        count <- ran.get
      yield assertTrue(stripIds(html) == "<div>built</div>", count == 1)
    },
    test("data-ascent stamp equals AstId.renderAttr(AstId.compute(node)) under IdMode.Hash") {
      val node = el("section", children = Vector(UI.Text("z")))
      for html <- Html.render(node, IdMode.Hash)
      yield
        val expected = AstId.renderAttr(AstId.compute(node))
        assertTrue(html == s"""<section data-ascent="$expected">z</section>""")
    },
    test("ServerRegion renders an empty container with id + server-region marker") {
      for html <- Html.render(UI.ServerRegion("cart"))
      yield assertTrue(
        stripIds(html) == """<div id="cart" data-ascent-server-region="cart"></div>"""
      )
    },
    test("ServerRegion honors a custom tag and escapes its id") {
      for html <- Html.render(UI.ServerRegion("a\"b", "section"))
      yield assertTrue(
        stripIds(html) == """<section id="a&quot;b" data-ascent-server-region="a&quot;b"></section>"""
      )
    },
  )
end HtmlRenderSpec
