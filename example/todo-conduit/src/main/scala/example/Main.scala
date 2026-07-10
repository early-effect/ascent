package example

import ascent.*
import zio.*

/** Entry point. `scalaJSUseMainModuleInitializer := true` makes the linker call `Main.main` at module load.
  *
  * No style-install step: the tree's component `CssClass`/`Keyframes` and `App.Chrome` (declared on the root)
  * self-register, and [[AscentApp.mountBody]] flushes them into `<head>` before mounting.
  */
object Main extends ZIOAppDefault:

  def run =
    for
      c  <- todo.TodoApp.make()
      ui <- todo.App.component(c.ctx)
      _  <- AscentApp.mountBody(ui)
      // Run the dispatch loop inline (no forkDaemon — a completed forking fiber lets daemons be reaped,
      // stalling the loop). Blocks for the page's lifetime since no Done is ever dispatched.
      _ <- c.run(false)
    yield ()
end Main
