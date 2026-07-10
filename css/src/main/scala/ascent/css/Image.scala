package ascent.css

/** A composable CSS `<image>` — `url(...)`, a typed [[Gradient]], or an `image-set(...)`. Feeds `background-image`,
  * `mask-image`, `list-style-image`, `border-image-source`, `content`, etc.
  *
  * Attaches via the universal `apply(CssValue)` overload. A bare [[Gradient]] is already a `CssValue`, so it can attach
  * directly; [[Image.gradient]] wraps it as an `Image` when you want the value typed as such (e.g. inside `imageSet`).
  */
sealed trait Image extends CssValue:
  override def toString: String = render

object Image:

  /** `url("…")` — the URL is quoted (it may contain characters illegal in an unquoted `url-token`). */
  final case class Url(href: String) extends Image:
    def render: String = s"""url("$href")"""

  /** A gradient used where an `<image>` is expected. */
  final case class Grad(gradient: Gradient) extends Image:
    def render: String = gradient.render

  /** `image-set(...)` — comma-separated candidates, each an image + a resolution/format descriptor (`"a.png" 1x`). */
  final case class ImageSet(candidates: List[String]) extends Image:
    def render: String = s"image-set(${candidates.mkString(", ")})"

  def url(href: String): Image             = Url(href)
  def gradient(g: Gradient): Image         = Grad(g)
  def imageSet(candidates: String*): Image = ImageSet(candidates.toList)
end Image
