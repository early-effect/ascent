package ascent.dsl

import ascent.ast.{AscentEvent, Attr}
import ascent.domtypes.Events
import zio.*
import zio.test.*

object EventKeySpec extends ZIOSpecDefault:

  def spec = suite("EventKey DSL (apply -> Attr.EventHandler)")(
    test("Events.onInput(handler) builds an EventHandler carrying the DOM event name") {
      val attr = Events.onInput(_ => ZIO.unit)
      attr match
        case Attr.EventHandler(name, _) => assertTrue(name == "input")
        case other                      => assertTrue(false, other.toString.nonEmpty)
    },
    test("the handler receives an AscentEvent and can read targetValue (two-way binding)") {
      for
        ref <- Ref.make("")
        attr = Events.onInput(e => ref.set(e.targetValue.getOrElse("<none>")))
        h    = attr.asInstanceOf[Attr.EventHandler[Any]].handler
        _   <- h(AscentEvent.simple(targetValue = Some("hi")))
        got <- ref.get
      yield assertTrue(got == "hi")
    },
    test("the handler can read key (Enter/Escape submit semantics)") {
      for
        ref <- Ref.make(Option.empty[String])
        attr = Events.onKeyDown(e => ref.set(e.key))
        h    = attr.asInstanceOf[Attr.EventHandler[Any]].handler
        _   <- h(AscentEvent.simple(key = Some("Enter")))
        got <- ref.get
      yield assertTrue(got.contains("Enter"))
    },
    test("negative: an event with no targetValue yields None (no crash, no default substitution)") {
      for
        ref <- Ref.make(Option("sentinel"))
        attr = Events.onChange(e => ref.set(e.targetValue))
        h    = attr.asInstanceOf[Attr.EventHandler[Any]].handler
        _   <- h(AscentEvent.simple(targetValue = None))
        got <- ref.get
      yield assertTrue(got.isEmpty)
    },
    test("regression: the sugar is a FAITHFUL wrapper — identical to the raw Attr.EventHandler form") {
      for
        viaDsl <- ZIO.succeed(Events.onClick(_ => ZIO.unit))
        viaRaw <- ZIO.succeed(Attr.EventHandler("click", (_: AscentEvent) => ZIO.unit))
        n1 = viaDsl.asInstanceOf[Attr.EventHandler[Any]].event
        n2 = viaRaw.event
      yield assertTrue(n1 == n2, viaDsl.isInstanceOf[Attr.EventHandler[?]])
    },
    test("`.sync` lifts a side-effecting (AscentEvent => Unit) block into an EventHandler on the right event") {
      val attr = Events.onInput.sync(_ => ())
      assertTrue(attr.asInstanceOf[Attr.EventHandler[Any]].event == "input")
    },
    test("`.sync` actually runs the side effect when its handler effect is executed") {
      var seen = "<unset>"
      val attr = Events.onInput.sync(e => seen = e.targetValue.getOrElse("?"))
      val h    = attr.asInstanceOf[Attr.EventHandler[Any]].handler
      for _ <- h(AscentEvent.simple(targetValue = Some("typed")))
      yield assertTrue(seen == "typed")
    },
  )
end EventKeySpec
