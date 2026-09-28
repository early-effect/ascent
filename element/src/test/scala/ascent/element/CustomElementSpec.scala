package ascent.element

import ascent.dom
import zio.*
import zio.test.*

object CustomElementSpec extends ZIOSpecDefault:
  private val names = Gen
    .elements("a", "b", "c", "x9", "q")
    .flatMap(h => Gen.alphaNumericStringBounded(0, 6).map(t => s"$h-${t.toLowerCase}"))

  /** Connect `el` to the document, as a page would; jsdom always has a body. */
  private def attach(el: dom.Element): UIO[Unit] =
    ZIO.succeed(dom.document.body.foreach(_.appendChild(el)))

  def spec = suite("CustomElement")(
    test("an instance the browser constructs reports each time the document gains and loses it"):
      ZIO.scoped(
        for
          defined <- CustomElement.define(ElementName("spec-lifecycle"))
          seen    <- defined.lifecycle.take(4).runCollect.fork
          el      <- defined.create
          _       <- attach(el)
          _       <- ZIO.succeed(el.remove())
          _       <- attach(el)
          _       <- ZIO.succeed(el.remove())
          events  <- seen.join
        yield assertTrue(
          events.map {
            case ElementLifecycle.Connected(_)    => "connected"
            case ElementLifecycle.Disconnected(_) => "disconnected"
          } == Chunk("connected", "disconnected", "connected", "disconnected"),
          events.forall(e => scala.scalajs.js.special.strictEquals(e.element, el)),
        )
      )
    ,
    test("markup the parser meets is an instance too"):
      ZIO.scoped(
        for
          defined <- CustomElement.define(ElementName("spec-markup"))
          seen    <- defined.lifecycle.take(1).runCollect.fork
          host    <- ZIO.succeed(dom.document.createElement("div"))
          _       <- ZIO.succeed(host.innerHTML = "<spec-markup></spec-markup>") *> attach(host)
          events  <- seen.join
          _       <- ZIO.succeed(host.remove())
        yield assertTrue(events.headOption.exists(_.isInstanceOf[ElementLifecycle.Connected]))
      )
    ,
    test("a page defines a name once"):
      ZIO.scoped(
        for
          _     <- CustomElement.define(ElementName("spec-once"))
          again <- CustomElement.define(ElementName("spec-once")).flip
        yield assertTrue(again == DefineError.AlreadyDefined(ElementName("spec-once")))
      )
    ,
    test("a name is lowercase, starts with a letter, and has a hyphen"):
      check(names)(n => assertTrue(ElementName.from(n).map(_.value) == Right(n))) &&
      assertTrue(
        ElementName.from("nohyphen").isLeft,
        ElementName.from("Upper-case").isLeft,
        ElementName.from("my-Element").isLeft,
        ElementName.from("1-starts-with-digit").isLeft,
        ElementName.from("has space-x").isLeft,
        ElementName.from("font-face") == Left(ElementNameError.Reserved("font-face")),
      )
    ,
    test("a literal that is not a name does not compile"):
      typeCheck("""ascent.element.ElementName("NoHyphen")""").map(r => assertTrue(r.isLeft)),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
end CustomElementSpec
