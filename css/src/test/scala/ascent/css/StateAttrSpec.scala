package ascent.css

import ascent.ast.Attr
import ascent.domtypes.AttrValue
import ascent.squawk.sq
import zio.*
import zio.test.*

/** [[StateAttr]] centralizes a state-tracking HTML attribute (typically `data-state`) into one value that produces both
  * the AST-side [[ascent.ast.Attr.ReactiveAttr]] and the CSS-side [[Selector]], so the attribute name can't drift
  * between the element and the CSS that targets it.
  */
object StateAttrSpec extends ZIOSpecDefault:

  def spec = suite("StateAttr")(
    test("toAttr produces a ReactiveAttr under the configured name") {
      for
        state <- sq("idle")
        s = StateAttr.dataState(state)
      yield s.toAttr match
        case Attr.ReactiveAttr("data-state", _) => assertCompletes
        case other                              => assertNever(s"expected ReactiveAttr(\"data-state\", _), got $other")
    },
    test("the ReactiveAttr's Squawk encodes each transition as AttrValue.Str on observers") {
      for
        state <- sq("idle")
        s    = StateAttr.dataState(state)
        attr = s.toAttr.asInstanceOf[Attr.ReactiveAttr]
        // observe is change-only, so read the current value separately before any transitions.
        v0   <- attr.value.get
        sink <- Ref.make(Vector.empty[AttrValue])
        sub  <- attr.value.observe(v => sink.update(_ :+ v))
        _    <- state.set("loading")
        _    <- state.set("error")
        seen <- sink.get
        _    <- sub.cancel
      yield assertTrue(
        v0 == AttrValue.Str("idle"),
        seen == Vector(AttrValue.Str("loading"), AttrValue.Str("error")),
      )
    },
    test("whenIs(value, members*) produces a Selector matching `[name='value']`") {
      for
        state <- sq("idle")
        s   = StateAttr.dataState(state)
        sel = s.whenIs("loading", Declaration("opacity", "0.5"))
      yield
        val rendered = sel.render
        assertTrue(
          rendered.contains("[data-state='loading'] {"),
          rendered.contains("opacity: 0.5;"),
        )
    },
    test("nesting whenIs inside a CssScope chains scope + state into one combinator-free selector") {
      val root     = ascent.ast.UI.Element("div", Vector.empty, Vector.empty)
      val rootAttr = ascent.ast.AstId.renderAttr(ascent.ast.AstId.compute(root))
      for
        state <- sq("idle")
        s = StateAttr.dataState(state)
      yield
        object scope
            extends CssScope(
              root,
              Declaration("color", "black"),
              s.whenIs("loading", Declaration("opacity", "0.5")),
            )
        val css = scope.renderCss
        assertTrue(
          css.contains(s"""[data-ascent="$rootAttr"] {"""),
          css.contains(s"""[data-ascent="$rootAttr"][data-state='loading'] {"""),
          css.contains("opacity: 0.5;"),
        )
      end for
    },
    test("a custom-named StateAttr targets a non-`data-state` attribute") {
      for
        mode <- sq("dark")
        s = StateAttr("data-theme", mode)
      yield
        val sel = s.whenIs("dark", Declaration("background", "black"))
        assertTrue(
          s.toAttr.asInstanceOf[Attr.ReactiveAttr].name == "data-theme",
          sel.render.contains("[data-theme='dark'] {"),
        )
    },
    test("an empty whenIs body emits no selector block rather than crashing") {
      for
        state <- sq("idle")
        s = StateAttr.dataState(state)
      yield
        val rendered = s.whenIs("loading").render
        assertTrue(rendered.isEmpty || !rendered.contains("opacity"))
    },
  )
end StateAttrSpec
