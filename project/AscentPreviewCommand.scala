package sbt

/** Reach the live sbt session from a background watch job.
  *
  * `CommandChannel.append` is public. `StandardMain.exchange` is `private[sbt]`, so this helper lives in `package sbt`.
  * Appends to the first channel that accepts the exec (console, then network). `source = None` so a paused channel
  * cannot drop the rebuild.
  */
object AscentPreviewCommand:
  def submit(commandLine: String): Boolean =
    val exec = Exec(commandLine, None)
    StandardMain.exchange.channels.exists(_.append(exec))

  /** Latest State the command loop stored. Null before the first prompt. */
  def withCurrentState[A](f: State => A): A =
    StandardMain.exchange.withState(f)
