package ascent.chekhov

/** Maps an [[HtmlTag]] marker onto a handle constructor.
  *
  * `ElementKey` is unparameterized (`val button: ElementKey`), so a given on `Elements.button.type` is not inferred
  * from `E.button`. Callers pass [[HtmlTag.button]] (or `root.button("inc")`) so the handle kind is a compile-time
  * fact. Named `HtmlTag`, not `Tag`, to stay clear of `zio.Tag`.
  */
trait TagHandle[K]:
  type Handle[F[_]] <: ElementHandle[F]
  def tagName: String
  def wrap[F[_]](selector: String, backend: HandleBackend[F]): Handle[F]

/** Singleton markers that preserve distinct types under inference (`HtmlTag.input` vs `HtmlTag.button`). */
object HtmlTag:
  object input
  object button
  object textarea
  object select

object TagHandle:
  given input: TagHandle[HtmlTag.input.type] with
    type Handle[F[_]] = InputHandle[F]
    def tagName: String                                                         = "input"
    def wrap[F[_]](selector: String, backend: HandleBackend[F]): InputHandle[F] =
      InputHandle(selector, backend)

  given button: TagHandle[HtmlTag.button.type] with
    type Handle[F[_]] = ButtonHandle[F]
    def tagName: String                                                          = "button"
    def wrap[F[_]](selector: String, backend: HandleBackend[F]): ButtonHandle[F] =
      ButtonHandle(selector, backend)

  given textarea: TagHandle[HtmlTag.textarea.type] with
    type Handle[F[_]] = TextAreaHandle[F]
    def tagName: String                                                            = "textarea"
    def wrap[F[_]](selector: String, backend: HandleBackend[F]): TextAreaHandle[F] =
      TextAreaHandle(selector, backend)

  given select: TagHandle[HtmlTag.select.type] with
    type Handle[F[_]] = SelectHandle[F]
    def tagName: String                                                          = "select"
    def wrap[F[_]](selector: String, backend: HandleBackend[F]): SelectHandle[F] =
      SelectHandle(selector, backend)
end TagHandle
