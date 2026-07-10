package ascent.domgen

import org.scalafmt.interfaces.Scalafmt

import java.nio.file.{Path, Paths}

/** Formats generated source through the project's own `.scalafmt.conf`, so `domgen` emits already-formatted files and
  * the formatting rules live in exactly one place (the config, not copied into the render templates). Backed by
  * scalafmt-dynamic, which resolves and runs the pinned scalafmt version in its own classloader.
  */
object Format:

  private val configPath: Path   = Paths.get(".scalafmt.conf")
  private val scalafmt: Scalafmt = Scalafmt.create(getClass.getClassLoader)

  /** Format `code` as if it lived at `target` (the extension/path drives scalafmt's dialect selection). Returns the
    * formatted source; scalafmt reports syntax errors to its default reporter and returns the input unchanged, so a
    * malformed emit still writes something readable.
    *
    * Runs to a fixed point: a single scalafmt pass is not idempotent here — reflowing long scaladoc changes a block's
    * line count, which flips the `insertEndMarkerMinLines` decision on the NEXT pass. `sbt scalafmt` effectively gets
    * that second pass on the committed file, so formatting once would leave the generated output perpetually "needs
    * formatting". Iterate until stable (scalafmt converges in two; cap guards against a config that never does).
    */
  def apply(target: Path, code: String): String =
    def loop(current: String, remaining: Int): String =
      val next = scalafmt.format(configPath, target, current)
      if next == current || remaining == 0 then next else loop(next, remaining - 1)
    loop(code, 4)

end Format
