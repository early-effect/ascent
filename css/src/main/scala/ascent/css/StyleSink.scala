package ascent.css

import zio.*

/** Pluggable destination for CSS rule blocks emitted by [[CssClass]] instances.
  *
  * Authoring a stylesheet is platform-neutral (jvm/js/native) — a [[CssClass]] is a value, its rules are strings. Where
  * the strings GO is platform-specific:
  *   - on JS we want to inject a `<style>` element into `<head>` so the browser applies them.
  *   - on JVM/native we want a no-op (or, eventually, a string buffer for SSR).
  *
  * Each [[CssClass]] writes once per class, keyed by its auto-derived class name (the `key` argument). Re-appending the
  * same key replaces the previous block — needed because `CssClass` may be referenced from multiple components but only
  * one `<style>` should exist.
  */
trait StyleSink:
  /** Add or replace a CSS block keyed by `key`. Calling twice with the same key replaces. */
  def append(key: String, css: String): UIO[Unit]

object StyleSink:

  /** Discards every block. Used by JVM/native authoring code that doesn't have a DOM. */
  val noop: StyleSink = new StyleSink:
    def append(key: String, css: String): UIO[Unit] = ZIO.unit

  /** A test-friendly sink that records every appended block in registration order. Re-appending the same key replaces
    * the prior entry's value but keeps it in its original position (so iteration order is stable across replacements).
    */
  trait Capturing extends StyleSink:
    def captured: UIO[Vector[(String, String)]]

  def capturing: UIO[Capturing] =
    Ref.make(Vector.empty[(String, String)]).map { ref =>
      new Capturing:
        def append(key: String, css: String): UIO[Unit] =
          ref.update { v =>
            v.indexWhere(_._1 == key) match
              case -1  => v :+ (key -> css)
              case idx => v.updated(idx, key -> css)
          }
        def captured: UIO[Vector[(String, String)]] = ref.get
    }
end StyleSink
