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
      dom.document.documentElement.foreach(_.appendChild(div))
      div
    })(div => ZIO.succeed(div.parentNode.foreach(_.removeChild(div))))

  private def text(el: dom.Element, tag: String): Option[String] =
    el.querySelector(tag).flatMap(_.textContent)

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
            case Some(b: dom.HTMLButtonElement) => b.click()
            case _                              => ())
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
      }
    ,
    test("subscribe is not sent when the host does not offer it"):
      ZIO.scoped {
        for
          seen         <- Ref.make(Chunk.empty[Int])
          failed       <- Ref.make(Option.empty[SubscribeError])
          methods      <- Ref.make(Chunk.empty[String])
          (view, back) <- ViewPort.pair
          _            <- answering(back, methods, subscribe = false)
          el           <- parent
          _            <- watcher(seen, failed).mount(view, el, AppInfo("counter-view", "1"))
          _            <- click(el)
          err          <- failed.get.repeatUntil(_.isDefined)
          asked        <- methods.get
        yield assertTrue(
          err.contains(SubscribeError.NotOffered),
          !asked.contains("resources/subscribe"),
        )
      }
    ,
    test("subscribe reads, follows updates, and unsubscribes when the view is torn down"):
      ZIO.scoped {
        for
          seen         <- Ref.make(Chunk.empty[Int])
          failed       <- Ref.make(Option.empty[SubscribeError])
          methods      <- Ref.make(Chunk.empty[String])
          (view, back) <- ViewPort.pair
          _            <- answering(back, methods, subscribe = true)
          el           <- parent
          mounted      <- watcher(seen, failed).mount(view, el, AppInfo("counter-view", "1"))
          _            <- click(el)
          _            <- ZIO.sleep(200.millis)
          _            <- back.send(
            Message.Notification("notifications/resources/updated", Json.Obj("uri" -> Json.Str("notes://board")))
          )
          _      <- ZIO.sleep(200.millis)
          second <- seen.get
          _      <- back.send(HostRequest.ResourceTeardown(None).message(RequestId.Num(9)))
          _      <- ZIO.sleep(200.millis)
          asked  <- methods.get
          err    <- failed.get
          closed <- mounted.closed.timeout(500.millis)
        yield assertTrue(
          second == Chunk(1, 2),
          err.isEmpty,
          asked.contains("resources/unsubscribe"),
          closed.isDefined,
        )
      },
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds)

  private def watcher(seen: Ref[Chunk[Int]], failed: Ref[Option[SubscribeError]]) =
    McpApp(shed).view { (_, bridge) =>
      E.button(
        Ev.onClick(_ =>
          bridge
            .subscribe[Count]("notes://board")(count => seen.update(_ :+ count.value))
            .tapError(err => failed.set(Some(err)))
            .ignore
        ),
        "watch",
      )
    }

  private def click(el: dom.Element): UIO[Unit] =
    ZIO.succeed {
      el.querySelector("button") match
        case Some(button: dom.HTMLButtonElement) => button.click()
        case _                                   => ()
    }

  /** Answers the handshake and resource calls. Each `resources/read` returns the next count. */
  private def answering(port: ViewPort, methods: Ref[Chunk[String]], subscribe: Boolean): URIO[Scope, Unit] =
    val capabilities =
      if subscribe then
        HostCapabilities(experimental = Some(Json.Obj("serverResources" -> Json.Obj("subscribe" -> Json.Bool(true)))))
      else HostCapabilities()
    val init = InitializeResult(UiProtocol.Version, Implementation("host", "1"), capabilities, HostContext.empty)
    Ref.make(0).flatMap { reads =>
      port.receive
        .foreach {
          case Message.Request(id, method, _) =>
            methods.update(_ :+ method) *> (method match
              case "resources/read" =>
                reads.updateAndGet(_ + 1).flatMap { n =>
                  val body = ResourceContents.Text("notes://board", Some("application/json"), s"""{"value":$n}""", None)
                  port.send(Message.Result(id, json(ReadResourceResult(Chunk(body)))))
                }
              case "ui/initialize" => port.send(Message.Result(id, json(init)))
              case _               => port.send(Message.Result(id, Json.Obj())))
          case _ => ZIO.unit
        }
        .ignore
        .forkScoped
        .unit
    }
  end answering
end McpAppSpec
