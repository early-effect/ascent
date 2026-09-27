package ascent.mcpapp

import ascent.*
import ascent.dsl.*
import heddle.mcp.apps.{Grant, Shed, UiUri}
import heddle.mcp.apps.ui.*
import heddle.mcp.protocol.*
import heddle.{/, Endpoint, Schema}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

final case class Count(value: Int) derives Schema, JsonCodec

object McpAppSpec extends ZIOSpecDefault:
  private val show = Endpoint.get("counter").out[Count].name("show_counter")
  private val inc  = Endpoint.post("counter" / "inc").out[Count].name("inc")
  private val shed = Shed(UiUri("ui://counter/view"), "Counter", Grant.launch(show))((inc = Grant.app(inc)))

  private val app = McpApp(shed).view { (run, bridge) =>
    E.div(
      E.output(run.map {
        case Run.Returned(_, count) => s"count ${count.value}"
        case _                      => "waiting"
      }),
      E.button(Ev.onClick(_ => bridge.call(_.inc)(()).ignore), "+"),
    )
  }

  private def json[A: JsonEncoder](a: A): Json.Obj =
    a.toJsonAST.toOption.collect { case o: Json.Obj => o }.getOrElse(Json.Obj())

  private val theme = HostContext(styles = Some(HostStyles(Map(HostVar.ColorBackgroundPrimary -> "#101010"))))

  /** A host that answers the handshake and `inc`, and counts the calls it served. */
  private def host(port: ViewPort, calls: Ref[Int]): URIO[Scope, Unit] =
    port.receive
      .foreach {
        case Message.Request(id, "ui/initialize", _) =>
          port.send(
            Message.Result(
              id,
              json(InitializeResult(UiProtocol.Version, Implementation("host", "1"), HostCapabilities(), theme)),
            )
          )
        case Message.Request(id, "tools/call", _) =>
          calls.updateAndGet(_ + 1).flatMap { n =>
            port.send(Message.Result(id, json(CallToolResult(Chunk.empty, Some(Json.Obj("value" -> Json.Num(n)))))))
          }
        case _ => ZIO.unit
      }
      .ignore
      .forkScoped
      .unit

  private val parent: URIO[Scope, dom.Element] =
    ZIO.acquireRelease(ZIO.succeed {
      val div = dom.document.createElement("div")
      val _   = dom.document.documentElement.appendChild(div)
      div
    })(div => ZIO.succeed(div.parentNode.removeChild(div)).unit)

  private def text(el: dom.Element, tag: String): Option[String] =
    Option(el.querySelector(tag)).map(_.textContent)

  def spec = suite("McpApp")(
    test("the view renders the launch tool's run, typed from the shed, and themes :root from the host"):
      ZIO.scoped {
        for
          calls        <- Ref.make(0)
          (view, back) <- ViewPort.pair
          _            <- host(back, calls)
          el           <- parent
          mounted      <- app.mount(view, el, AppInfo("counter-view", "1"))
          before = text(el, "output")
          _ <- back.send(HostNotification.ToolInput(Json.Obj()).message)
          _ <- back.send(
            HostNotification.ToolResult(CallToolResult(Chunk.empty, Some(Json.Obj("value" -> Json.Num(7))))).message
          )
          _     <- mounted.run.get.repeatUntil { case Run.Returned(_, _) => true; case _ => false }
          after <- ZIO.succeed(text(el, "output")).repeatUntil(_.contains("count 7"))
          // assertTrue reads its values after the scope closes, when the view is gone: read the DOM here.
          style = text(el, "style")
        yield assertTrue(
          before.contains("waiting"),
          after.contains("count 7"),
          style.exists(_.contains(":root { --color-background-primary: #101010; }")),
        )
      }
    ,
    test("a click calls the shed's grant through the host"):
      ZIO.scoped {
        for
          calls        <- Ref.make(0)
          (view, back) <- ViewPort.pair
          _            <- host(back, calls)
          el           <- parent
          _            <- app.mount(view, el, AppInfo("counter-view", "1"))
          _            <- ZIO.succeed(el.querySelector("button") match
            case b: dom.HTMLButtonElement => b.click()
            case _                        => ())
          served <- calls.get.repeatUntil(_ == 1)
        yield assertTrue(served == 1)
      }
    ,
    test("the host's teardown unmounts the view before the view answers"):
      ZIO.scoped {
        for
          calls        <- Ref.make(0)
          (view, back) <- ViewPort.pair
          _            <- host(back, calls)
          el           <- parent
          mounted      <- app.mount(view, el, AppInfo("counter-view", "1"))
          mountedButton = text(el, "button")
          _ <- back.send(HostRequest.ResourceTeardown(None).message(RequestId.Num(9)))
          _ <- mounted.closed
          // Read inside the scope: after it closes, the view is gone whether teardown worked or not.
          afterButton = text(el, "button")
        yield assertTrue(mountedButton.contains("+"), afterButton.isEmpty)
      },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)
end McpAppSpec
