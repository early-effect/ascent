package ascent.element

import ascent.dom
import scala.quoted.*
import scala.scalajs.js
import zio.*
import zio.stream.ZStream

/** Why text is not a name the browser will define a custom element under. */
enum ElementNameError(val message: String):
  case NoHyphen(raw: String)     extends ElementNameError(s"'$raw' has no '-': a custom element name must")
  case NotLowercase(raw: String) extends ElementNameError(s"'$raw' must start with a-z and hold no A-Z")
  case BadCharacter(raw: String, char: Char)
      extends ElementNameError(s"'$char' in '$raw' is not one of a-z, 0-9, '-', '.', '_'")
  case Reserved(raw: String) extends ElementNameError(s"'$raw' is reserved by HTML")

/** A custom element's tag name (`ascent-mcp-view`), in HTML's rule: a lowercase letter first, a hyphen, and no
  * uppercase. This is the ASCII subset of the rule, which is all ascent defines.
  */
opaque type ElementName = String

object ElementName:
  private val reserved = Set(
    "annotation-xml",
    "color-profile",
    "font-face",
    "font-face-src",
    "font-face-uri",
    "font-face-format",
    "font-face-name",
    "missing-glyph",
  )

  def from(raw: String): Either[ElementNameError, ElementName] =
    if raw.isEmpty || !raw.head.isLower || !raw.head.isLetter then Left(ElementNameError.NotLowercase(raw))
    else if !raw.contains('-') then Left(ElementNameError.NoHyphen(raw))
    else if reserved.contains(raw) then Left(ElementNameError.Reserved(raw))
    else
      raw.find(c => !(c.isDigit || (c >= 'a' && c <= 'z') || c == '-' || c == '.' || c == '_')) match
        case Some(c) if c.isUpper => Left(ElementNameError.NotLowercase(raw))
        case Some(c)              => Left(ElementNameError.BadCharacter(raw, c))
        case None                 => Right(raw)

  /** A literal, checked at compile time. A runtime value goes through `from`. */
  inline def apply(inline raw: String): ElementName = ${ literal('raw) }

  extension (n: ElementName) def value: String = n

  private def literal(raw: Expr[String])(using Quotes): Expr[ElementName] =
    import quotes.reflect.report
    raw.value.map(s => (s, from(s))) match
      case None                => report.errorAndAbort("ElementName(...) takes a literal; use ElementName.from")
      case Some((_, Left(e)))  => report.errorAndAbort(s"not a custom element name: ${e.message}")
      case Some((s, Right(_))) => Expr(s)
end ElementName

/** Why a page could not define an element. */
enum DefineError(val message: String):
  case AlreadyDefined(name: ElementName) extends DefineError(s"${name.value} is already defined on this page")
  case Refused(name: ElementName, reason: String)
      extends DefineError(s"the browser would not define ${name.value}: $reason")

/** What happened to one instance of a defined element. */
enum ElementLifecycle:
  case Connected(element: dom.HTMLElement)
  case Disconnected(element: dom.HTMLElement)

  def element: dom.HTMLElement

/** A custom element this page defined. `lifecycle` follows every instance as the document gains and loses it. */
final class CustomElement private (val name: ElementName, hub: Hub[ElementLifecycle]):
  /** A new, unattached instance. */
  def create: UIO[dom.HTMLElement] =
    ZIO.succeed(dom.document.createElement(name.value)).flatMap {
      case e: dom.HTMLElement => ZIO.succeed(e)
      case other              => ZIO.dieMessage(s"${name.value} did not construct an HTMLElement: $other")
    }

  /** Every instance's connections and disconnections from the moment this returns, for as long as the scope lasts. The
    * subscription is made before it returns, so nothing that happens after is missed; a stream that subscribed only
    * once it ran would lose what happened in between.
    */
  def lifecycle: ZIO[Scope, Nothing, ZStream[Any, Nothing, ElementLifecycle]] = ZStream.fromHubScoped(hub)

  /** `lifecycle` for one instance: its connections and disconnections, in order. */
  def of(element: dom.HTMLElement): ZIO[Scope, Nothing, ZStream[Any, Nothing, ElementLifecycle]] =
    lifecycle.map(_.filter(e => js.special.strictEquals(e.element, element)))
end CustomElement

object CustomElement:
  /** Defines `name` for the page. The browser keeps a definition for the page's life; `lifecycle` reports instances for
    * as long as the scope lasts.
    */
  def define(name: ElementName): ZIO[Scope, DefineError, CustomElement] =
    for
      hub     <- ZIO.acquireRelease(Hub.unbounded[ElementLifecycle])(_.shutdown)
      defined <- Promise.make[DefineError, Unit]
      // The element class's callbacks enter ZIO through this stream's emitter, so the class is defined inside it.
      _ <- ZStream
        .asyncScoped[Any, Nothing, ElementLifecycle] { emit =>
          def tell(e: ElementLifecycle): Unit =
            val _ = emit(ZIO.succeed(Chunk.single(e)))
          register(name, tell).exit.flatMap(defined.done)
        }
        .foreach(hub.publish)
        .forkScoped
      _ <- defined.await
    yield CustomElement(name, hub)

  private def register(name: ElementName, tell: ElementLifecycle => Unit): IO[DefineError, Unit] =
    // A JS class local to this call, so its callbacks close over `tell`; the browser constructs it with `new`.
    class Element extends dom.HTMLElement:
      def connectedCallback(): Unit    = tell(ElementLifecycle.Connected(this))
      def disconnectedCallback(): Unit = tell(ElementLifecycle.Disconnected(this))

    ZIO.whenZIODiscard(ZIO.succeed(!js.isUndefined(dom.window.customElements.get(name.value))))(
      ZIO.fail(DefineError.AlreadyDefined(name))
    ) *>
      // The browser answers a name it will not take by throwing; that throw is the refusal.
      ZIO
        .attempt(dom.window.customElements.define(name.value, js.constructorOf[Element]))
        .mapError(e => DefineError.Refused(name, e.getMessage))
  end register
end CustomElement
