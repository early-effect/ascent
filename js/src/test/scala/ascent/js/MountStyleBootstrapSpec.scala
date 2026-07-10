package ascent.js

import ascent.*
import ascent.dsl.*
import ascent.ast.UI
import ascent.css.{CssClass, Declaration, Frame, GlobalRule, GlobalStyle, Keyframes}
import ascent.squawk.sq
import zio.*
import zio.test.*

import scala.scalajs.js

/** Mounting a UI whose tree references a [[CssClass]] / [[Keyframes]] / [[GlobalStyle]] injects its `<style>` into
  * `<head>` with no install step. Injection gates on the bearing element MOUNTING (its `Attr.Style` is recorded during
  * the walk), not on merely constructing the style object: a class on a mounted element lands at mount, while one only
  * inside a `when` thunk stays absent until the condition first goes true and the body builds.
  */
object MountStyleBootstrapSpec extends ZIOSpecDefault:

  object StaticCard   extends CssClass(Declaration("color", "rebeccapurple"))
  object SharedCard   extends CssClass(Declaration("color", "teal"))
  object ReactiveBase extends CssClass(Declaration("padding", "1px"))
  object ReactiveDone extends CssClass(Declaration("opacity", "0.5"))
  object LazyCard     extends CssClass(Declaration("color", "tomato"))

  object Wiggle   extends Keyframes("boot-wiggle", Frame.from(Declaration("transform", "rotate(0)")))
  object Animated extends CssClass(Wiggle.use("1s ease"))

  object TestChrome
      extends GlobalStyle(
        GlobalRule.raw("boot-page-body", "body { margin: 0; }"),
        ascent.css.FontFace(Declaration("font-family", "\"BootFace\"")),
      )

  private val cleanHead: UIO[Unit] = ZIO.succeed {
    val head  = dom.document.asInstanceOf[js.Dynamic].head
    val nodes = head.querySelectorAll("style[data-ascent-class]")
    val n     = nodes.length.asInstanceOf[Int]
    var i     = 0
    while i < n do
      head.removeChild(nodes.item(i).asInstanceOf[js.Dynamic])
      i += 1
  }

  private def styleCountForKey(key: String): Int =
    dom.document
      .asInstanceOf[js.Dynamic]
      .head
      .querySelectorAll(s"""style[data-ascent-class="$key"]""")
      .length
      .asInstanceOf[Int]

  private def styleBodyForKey(key: String): Option[String] =
    val node = dom.document
      .asInstanceOf[js.Dynamic]
      .head
      .querySelector(s"""style[data-ascent-class="$key"]""")
    if node == null || js.isUndefined(node) then None
    else Some(node.asInstanceOf[js.Dynamic].textContent.asInstanceOf[String])

  /** Count injected `<style>`s containing `fragment` — for "absent" checks phrased by content, not by key. */
  private def styleCountContaining(fragment: String): Int =
    val head  = dom.document.asInstanceOf[js.Dynamic].head
    val nodes = head.querySelectorAll("style[data-ascent-class]")
    val n     = nodes.length.asInstanceOf[Int]
    var count = 0
    var i     = 0
    while i < n do
      val body = nodes.item(i).asInstanceOf[js.Dynamic].textContent.asInstanceOf[String]
      if body.contains(fragment) then count += 1
      i += 1
    count
  end styleCountContaining

  private def withParent[A](use: dom.Element => UIO[A]): UIO[A] =
    ZIO.acquireReleaseWith(
      acquire = ZIO.succeed {
        val p = dom.document.createElement("div")
        dom.document.asInstanceOf[js.Dynamic].body.appendChild(p)
        p
      }
    )(release = p => ZIO.succeed(p.parentNode.removeChild(p)).unit)(use = use)

  def spec = suite("Mount (style bootstrap)")(
    test("a static CssClass passed as an element arg injects its <style> on mount") {
      withParent { parent =>
        for
          _ <- cleanHead
          _ <- AscentApp.mount(E.div(StaticCard, "hi"), parent)
        yield assertTrue(
          styleCountForKey(StaticCard.className) == 1,
          styleBodyForKey(StaticCard.className).exists(_.contains("color: rebeccapurple;")),
          styleBodyForKey(StaticCard.className).exists(_.contains(s".${StaticCard.className}")),
        )
      }
    },
    test("the same class on two elements injects exactly one <style>") {
      withParent { parent =>
        for
          _ <- cleanHead
          _ <- AscentApp.mount(E.div(E.div(SharedCard, "a"), E.div(SharedCard, "b")), parent)
        yield assertTrue(styleCountForKey(SharedCard.className) == 1)
      }
    },
    test("a reactive class whose CSS is contributed up-front injects at mount, before the squawk selects it") {
      // The class NAME is applied via a reactive `class` squawk; `.contribute` carries the CSS so it injects at mount
      // regardless of whether the squawk currently selects the class.
      withParent { parent =>
        val base = ReactiveBase.className
        val done = ReactiveDone.className
        for
          _   <- cleanHead
          cls <- sq(base)
          ui: UI[Any] = UI.Element(
            "div",
            Vector(ascent.ast.Attr.fromSquawk(A.className, cls), ReactiveBase.contribute, ReactiveDone.contribute),
            Vector.empty,
          )
          _              <- AscentApp.mount(ui, parent)
          presentAtMount <- ZIO.succeed(styleCountForKey(done) == 1)
          _              <- cls.set(s"$base $done")
          el = parent.asInstanceOf[js.Dynamic].firstChild
        yield assertTrue(
          styleCountForKey(base) == 1,
          presentAtMount,
          styleCountForKey(done) == 1,
          el.getAttribute("class").asInstanceOf[String].contains(done),
        )
        end for
      }
    },
    test("a reactive class SET injects CSS when a class first appears and diffs `class` tokens on toggle") {
      // Squawk[Set[CssClass]]: base always on, done toggles. done's CSS should NOT be present until it first appears,
      // then the token diff removes it when it leaves — without disturbing base.
      withParent { parent =>
        val base = ReactiveBase.className
        val done = ReactiveDone.className
        for
          _       <- cleanHead
          classes <- sq(Set[ascent.css.CssClass](ReactiveBase))
          _       <- AscentApp.mount(E.div(classes), parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
          // At mount: base present (token + CSS), done's CSS absent (never selected yet).
          baseAtMount = styleCountForKey(base) == 1
          doneAbsent  = styleCountForKey(done) == 0
          baseTokenOn = el.getAttribute("class").asInstanceOf[String].contains(base)
          _ <- classes.set(Set[ascent.css.CssClass](ReactiveBase, ReactiveDone))
          doneNowIn = styleCountForKey(done) == 1 && el.getAttribute("class").asInstanceOf[String].contains(done)
          _ <- classes.set(Set[ascent.css.CssClass](ReactiveBase))
          doneTokenGone = !el.getAttribute("class").asInstanceOf[String].split(" ").contains(done)
          baseStillOn   = el.getAttribute("class").asInstanceOf[String].contains(base)
        yield assertTrue(baseAtMount, doneAbsent, baseTokenOn, doneNowIn, doneTokenGone, baseStillOn)
        end for
      }
    },
    test("a class applied only inside a when-thunk is absent until the condition goes true") {
      // Probe by body, not LazyCard.className — but note the real gate now is the when body MOUNTING (recording its
      // Attr.Style), not merely naming the class.
      withParent { parent =>
        for
          _    <- cleanHead
          cond <- sq(false)
          ui: UI[Any] = E.div(when(cond)(E.div(LazyCard, "lazy")))
          _             <- AscentApp.mount(ui, parent)
          absentWhenOff <- ZIO.succeed(styleCountContaining("color: tomato;") == 0)
          _             <- cond.set(true)
          presentWhenOn <- ZIO.succeed(styleCountContaining("color: tomato;") == 1)
        yield assertTrue(absentWhenOff, presentWhenOn)
      }
    },
    test("a hardcoded class string with no backing CssClass injects nothing") {
      withParent { parent =>
        for
          _ <- cleanHead
          ui: UI[Any] = UI.Element(
            "div",
            Vector(ascent.ast.Attr.StaticAttr("class", ascent.domtypes.AttrValue.Str("toggle"))),
            Vector.empty,
          )
          _ <- AscentApp.mount(ui, parent)
          el = parent.asInstanceOf[js.Dynamic].firstChild
        yield assertTrue(
          styleCountForKey("toggle") == 0,
          el.getAttribute("class").asInstanceOf[String] == "toggle",
        )
      }
    },
    test("a keyframe a class .use-s rides along — both <style>s injected") {
      withParent { parent =>
        for
          _ <- cleanHead
          _ <- AscentApp.mount(E.div(Animated, "spin"), parent)
        yield assertTrue(
          styleCountForKey(Animated.className) == 1,
          styleCountForKey("keyframes-boot-wiggle") == 1,
          styleBodyForKey("keyframes-boot-wiggle").exists(_.contains("@keyframes boot-wiggle")),
        )
      }
    },
    test("a GlobalStyle declared on the root injects every page-chrome block") {
      withParent { parent =>
        for
          _ <- cleanHead
          _ <- AscentApp.mount(E.body(TestChrome, E.div("content")), parent)
        yield assertTrue(
          styleCountForKey("boot-page-body") == 1,
          styleBodyForKey("boot-page-body").exists(_.contains("margin: 0;")),
          styleCountForKey("font-face-BootFace") == 1,
        )
      }
    },
    // sequential: assertions read the shared <head>, which every mount injects into.
  ) @@ TestAspect.sequential
end MountStyleBootstrapSpec
