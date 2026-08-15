package ascent.preview

import java.nio.file.Path

/** How [[Preview]] serves a static tree and (optionally) pushes a tab reload.
  *
  * `stamp` and `reloadPath` are relative to [[root]]. CORS is off by default so same-origin example servers stay
  * strict; split-origin consumers opt in.
  */
final case class PreviewConfig(
    root: Path,
    port: Int = 8765,
    stamp: Path = Path.of("assets/dev-stamp"),
    reloadPath: Path = Path.of("__ascent", "reload"),
    cors: Boolean = false,
)
