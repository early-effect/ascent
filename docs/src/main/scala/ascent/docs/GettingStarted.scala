package ascent.docs

import ascent.*
import ascent.dsl.*
import specular.*
import zio.test.*

/** First page: two imports, a live counter, and where to go next. */
object GettingStarted extends DocSpec:

  def doc = page("Getting started")(
    md"""
**ascent** is effect-native reactive UI for Scala 3. The UI is a pure value; `Squawk`s mark the
boundaries that can change; the mount engine patches only those nodes. No virtual DOM, no diffing.
""",
    section("Two imports")(
      md"""
A view file needs exactly two imports:

```scala
import ascent.*        // elements, attrs, aria, events, css, Ctx, Squawk, …
import ascent.dsl.*    // apply, when / forEach / scoped / fragment / text
```

`import ascent.*` unions whatever ascent modules are on your classpath (an open package shared
across the jars). Short aliases: `E`, `A`, `Aria`, `Ev`, `S`.
""",
      example {
        E.ul(
          E.li(E.code("E"), " - Elements"),
          E.li(E.code("A"), " - Attrs"),
          E.li(E.code("Ev"), " - TypedEvents"),
          E.li(E.code("S"), " - Styles"),
        )
      }.assert(ui => assertTrue(ui != null)),
    ),
    section("A live counter")(
      md"""
Mutable sources are effects (`sq(0)`). Binding a `Squawk[String]` as a child updates that text
node in place; the buttons never rebuild.
""",
      exampleIO {
        for count <- sq(0)
        yield E.div(
          E.button(Events.onClick(_ => count.update(_ - 1)), "-"),
          E.span(" ", count.map(_.toString), " "),
          E.button(Events.onClick(_ => count.update(_ + 1)), "+"),
        )
      }.interactive.assert(_ => assertTrue(true)),
    ),
    section("Install")(
      md"""
Published to Maven Central under `rocks.earlyeffect`. Cross-built for JVM, Scala.js, and Native.
Use `%%%` in a cross / JS / Native build (or `%%` on plain JVM):

```scala
libraryDependencies += "rocks.earlyeffect" %%% "ascent-core" % "<version>"
libraryDependencies += "rocks.earlyeffect" %%% "ascent-js"   % "<version>"  // browser mount
libraryDependencies += "rocks.earlyeffect" %%% "ascent-css"  % "<version>"  // optional
```

Most apps take `ascent-core` + `ascent-js`, then add `ascent-css` and `ascent-conduit` as needed.
See [Modules](modules.html) for the full table.
""",
      example {
        E.p("Next: ", E.a(A.href("squawk.html"), "Squawk"), " or ", E.a(A.href("dsl.html"), "the DSL"), ".")
      }.assert(_ => assertTrue(true)),
    ),
  )
end GettingStarted
