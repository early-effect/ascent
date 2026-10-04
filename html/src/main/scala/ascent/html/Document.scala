package ascent.html

import ascent.{A, E}
import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import ascent.dsl.*

/** An HTML document: charset, viewport, and title, then the caller's head nodes, then the body nodes.
  *
  * Head and body are separate argument lists. `script` (and `link`, `meta`, `style`) is legal in both places, so which
  * list a node is in is the placement. There is no tag heuristic.
  *
  * The head list is `UI[Any]`. A head node that needs an environment, or a CSP `meta` that must be the first head
  * child, does not fit this constructor: build `E.html(...)` by hand and pass it to [[Html.renderDocument]]. Charset,
  * viewport, and title are always first.
  *
  * `lang` defaults to `en` and is overridable. It is the `lang` attribute on `html`.
  */
object Document:

  /** `Document(title)(head*)(body*)`. `lang` defaults to `"en"`. */
  def apply(title: String, lang: String = "en"): DocumentBuilder =
    DocumentBuilder(title, lang)

  /** The head list. Applying it returns the body builder. An empty `()` is a document with no extra head nodes. */
  final class DocumentBuilder(title: String, lang: String):
    def apply(head: UI[Any]*): BodyBuilder =
      BodyBuilder(title, lang, head.toSeq)

  /** The body list. `R` is inferred from these nodes, not from the head list, so an empty head does not freeze `R`. */
  final class BodyBuilder(title: String, lang: String, head: Seq[UI[Any]]):
    def apply[R](body: UI[R]*): UI[R] =
      // `charset` on `meta` is a content attribute the HTML IDL does not reflect, so it is not an `A.*` key.
      val charset  = E.meta(Attr.StaticAttr("charset", AttrValue.Str("utf-8")))
      val viewport = E.meta(A.name("viewport"), A.content(ViewportContent))
      // A splatted Seq[Arg[Any]] makes the element constructor infer R = Nothing, because Arg is
      // contravariant. A fragment is one UI argument, so R stays the body's R, and mount expands it.
      val headUi = E.head(charset, viewport, E.title(title), UI.Fragment[Any](head.toVector))
      val bodyUi = E.body(UI.Fragment[R](body.toVector))
      E.html(A.lang(lang), headUi, bodyUi)
  end BodyBuilder

  private val ViewportContent = "width=device-width, initial-scale=1"
end Document
