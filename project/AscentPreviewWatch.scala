package ascent.preview.sbt

import java.nio.file.{Files, Path}

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import sbt.nio.Watch
import sbt.nio.file.{FileTreeView, Glob}

/** Polls `fileInputs` globs without `Keys.globalFileTreeRepository`.
  *
  * sbt 2 `Continuous` shares that repository across `~` channels; a second watch (or leftover) goes deaf. Preview owns
  * a private `FileTreeView` snapshot instead.
  */
private[sbt] object AscentPreviewWatch:

  enum Event:
    case Changed
    case Stop

  final case class Snapshot(stamps: Map[Path, Long])

  val pollInterval: FiniteDuration = 200.millis
  val antiEntropy: FiniteDuration  = Watch.defaultAntiEntropy

  def snapshot(globs: Seq[Glob]): Snapshot =
    val listed = FileTreeView.default.list(globs)
    Snapshot(
      listed.iterator
        .filter { (_, attrs) => attrs.isRegularFile }
        .map { (path, _) => path -> lastModified(path) }
        .toMap
    )

  /** Block until a globbed file changes, optional stdin newline/EOF, or interrupt. Coalesces bursts for
    * [[antiEntropy]].
    */
  def await(globs: Seq[Glob], previous: Snapshot, listenStdin: Boolean): Event =
    while true do
      if Thread.currentThread.isInterrupted then return Event.Stop
      if listenStdin && readStop() then return Event.Stop
      try Thread.sleep(pollInterval.toMillis)
      catch case _: InterruptedException => return Event.Stop
      if listenStdin && readStop() then return Event.Stop
      if snapshot(globs) != previous then
        val deadline = System.nanoTime() + antiEntropy.toNanos
        while System.nanoTime() < deadline do
          if listenStdin && readStop() then return Event.Stop
          val remainingMs = math.max(1L, (deadline - System.nanoTime()) / 1_000_000L)
          try Thread.sleep(math.min(50L, remainingMs))
          catch case _: InterruptedException => return Event.Stop
        if snapshot(globs) != previous then return Event.Changed
    end while
    throw new IllegalStateException("ascentPreview watch loop exited")
  end await

  private def lastModified(path: Path): Long =
    try Files.getLastModifiedTime(path).toMillis
    catch case NonFatal(_) => -1L

  /** Same stop keys as sbt `~`: Enter or Ctrl-D. Non-TTY/batch: only interrupt / watch-cycle limit. */
  private def readStop(): Boolean =
    if System.console() == null then false
    else
      try
        System.in.available() > 0 && {
          val c = System.in.read()
          c == '\n' || c == '\r' || c == 4 || c == -1
        }
      catch case NonFatal(_) => false
end AscentPreviewWatch
