package ascent.domgen

import zio.*

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Diagnostic for HTML attribute generation (see domgen/README.md to run).
  *
  * With no arguments, dumps a summary of how many attributes are surfaced per interface. With one argument
  * (`HTMLInputElement`), lists every attribute on that interface (own + inherited). With two (`HTMLInputElement
  * autofocus`), drills into the named attribute and shows its IDL type, codec, and generated dom name.
  */
object AttrInspect extends ZIOAppDefault:

  override val bootstrap: ZLayer[Any, Any, Any] = Runtime.removeDefaultLoggers

  private val webrefDir = Paths.get("data/webref")

  def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args <- getArgs
      _    <- inspect(args.toList)
    yield ()

  private def inspect(args: List[String]): Task[Unit] =
    for
      idl <- loadIdl()
      _   <- args match
        case Nil =>
          summary(idl)
        case List(name) =>
          // Tries interface, then dictionary, then enum, then callback. Whichever matches
          // is what the user gets.
          listAny(name, idl)
        case iface :: member :: _ =>
          showOne(iface, member, idl)
    yield ()

  private def listAny(name: String, idl: Webref.Idl): Task[Unit] =
    if idl.interfaces.contains(name) then listAttrs(name, idl)
    else
      idl.dictionaries.find(_.name == name) match
        case Some(d) => listDictionary(d)
        case None    =>
          idl.enums.find(_.name == name) match
            case Some(e) => listEnum(e)
            case None    =>
              idl.callbacks.find(_.name == name) match
                case Some(c) => listCallback(c)
                case None    => Console.printLine(s"$name not found in IDL").orDie

  private def listDictionary(d: Webref.IdlDictionary): Task[Unit] =
    for
      _ <- Console.printLine(s"## ${d.name} (dictionary)").orDie
      _ <- Console.printLine(s"  parent : ${d.inheritance.getOrElse("-")}").orDie
      _ <- Console.printLine(s"  fields : ${d.fields.size}").orDie
      _ <- ZIO.foreachDiscard(d.fields.sortBy(_.name)) { f =>
        val req = if f.required then " [required]" else ""
        Console.printLine(s"    ${f.name.padTo(24, ' ')} ${f.idlType}$req").orDie
      }
    yield ()

  private def listEnum(e: Webref.IdlEnum): Task[Unit] =
    for
      _ <- Console.printLine(s"## ${e.name} (enum)").orDie
      _ <- Console.printLine(s"  values : ${e.values.size}").orDie
      _ <- ZIO.foreachDiscard(e.values)(v => Console.printLine(s"    \"$v\"").orDie)
    yield ()

  private def listCallback(c: Webref.IdlCallback): Task[Unit] =
    for
      _ <- Console.printLine(s"## ${c.name} (callback)").orDie
      _ <- Console.printLine(s"  return : ${c.returnType}").orDie
      _ <- Console.printLine(s"  params :").orDie
      _ <- ZIO.foreachDiscard(c.params) { p =>
        val opt = if p.optional then " [optional]" else ""
        Console.printLine(s"    ${p.name.padTo(20, ' ')} ${p.idlType}$opt").orDie
      }
    yield ()

  private def loadIdl(): Task[Webref.Idl] =
    for
      files <- ZIO.attemptBlocking {
        val s = Files.list(webrefDir.resolve("idlparsed"))
        try s.iterator().asScala.toList.filter(_.toString.endsWith(".json")).sorted
        finally s.close()
      }
      idls <- ZIO.foreach(files): p =>
        ZIO
          .attemptBlocking(Files.readString(p, StandardCharsets.UTF_8))
          .flatMap(s => Webref.parseIdl(s).mapError(e => new RuntimeException(s"$p: $e")))
    yield Webref.mergeIdl(idls*)

  private def summary(idl: Webref.Idl): Task[Unit] =
    val summarized = idl.interfaces.toList
      .filter((name, _) => name.startsWith("HTML") || name == "Element" || name == "Node")
      .sortBy(_._1)
      .map { case (name, iface) =>
        val attrs = DefBuilder.attributesFor(name, idl).map(_.scalaName).sorted
        s"  $name (parent=${iface.inheritance.getOrElse("-")}, ${attrs.size} attrs after walk)"
      }
    Console.printLine(s"-- HTML interfaces (${idl.interfaces.size} total) --").orDie
      *> ZIO.foreachDiscard(summarized)(s => Console.printLine(s).orDie)
  end summary

  private def listAttrs(iface: String, idl: Webref.Idl): Task[Unit] =
    val attrs       = DefBuilder.attributesFor(iface, idl)
    val methods     = DefBuilder.methodsFor(iface, idl)
    val ifaceOpt    = idl.interfaces.get(iface)
    val mixinsInto  = idl.includes.filter(_.target == iface).map(_.mixin)
    val mixedIntoMe = idl.includes.filter(_.mixin == iface).map(_.target)
    for
      _ <- Console.printLine(s"## $iface").orDie
      _ <- Console
        .printLine(
          s"  type   : ${ifaceOpt.map(i => if i.isMixin then "interface mixin" else "interface").getOrElse("(missing)")}"
        )
        .orDie
      _ <- Console.printLine(s"  parent : ${ifaceOpt.flatMap(_.inheritance).getOrElse("-")}").orDie
      _ <- ZIO.when(mixinsInto.nonEmpty)(
        Console.printLine(s"  includes: ${mixinsInto.mkString(", ")}").orDie
      )
      _ <- ZIO.when(mixedIntoMe.nonEmpty)(
        Console.printLine(s"  mixed into: ${mixedIntoMe.mkString(", ")}").orDie
      )
      _ <- Console.printLine(s"  attrs (own + inherited + mixin): ${attrs.size}").orDie
      _ <- ZIO.foreachDiscard(attrs.sortBy(_.scalaName)) { a =>
        Console.printLine(s"    ${a.scalaName.padTo(24, ' ')} dom=${a.domName.padTo(24, ' ')} codec=${a.codec}").orDie
      }
      _ <- Console.printLine(s"  ops (own + inherited + mixin): ${methods.size}").orDie
      _ <- ZIO.foreachDiscard(methods.sortBy(_.scalaName)) { m =>
        val args = m.params.map(p => s"${p.name}: ${p.scalaType}${if p.optional then "?" else ""}").mkString(", ")
        Console.printLine(s"    ${m.scalaName.padTo(24, ' ')} (${args}) -> ${m.returnType}").orDie
      }
    yield ()
    end for
  end listAttrs

  private def showOne(iface: String, member: String, idl: Webref.Idl): Task[Unit] =
    // Look up `member` as an attribute first, then as an operation. Operations show full
    // signature including `optional` flags on each arg — that's the field that drives
    // the renderer's default-arg emission.
    val attrs   = DefBuilder.attributesFor(iface, idl)
    val methods = DefBuilder.methodsFor(iface, idl)

    attrs.find(_.scalaName == member) match
      case Some(d) =>
        for
          _ <- Console.printLine(s"## $iface.$member (attribute)").orDie
          _ <- Console.printLine(s"  dom name : ${d.domName}").orDie
          _ <- Console.printLine(s"  codec    : ${d.codec}").orDie
        yield ()
      case None =>
        methods.find(_.scalaName == member) match
          case Some(m) =>
            for
              _ <- Console.printLine(s"## $iface.$member (operation)").orDie
              _ <- Console.printLine(s"  dom name : ${m.domName}").orDie
              _ <- Console.printLine(s"  return   : ${m.returnType}").orDie
              _ <- Console.printLine(s"  params   :").orDie
              _ <- ZIO.foreachDiscard(m.params) { p =>
                val opt = if p.optional then "  [optional]" else ""
                Console.printLine(s"    ${p.name.padTo(20, ' ')} ${p.scalaType}$opt").orDie
              }
            yield ()
          case None =>
            for
              _ <- Console.printLine(s"## $iface.$member — NOT FOUND on this interface").orDie
              _ <-
                def chain(name: String): List[String] =
                  idl.interfaces.get(name) match
                    case Some(i) => name :: i.inheritance.toList.flatMap(chain)
                    case None    => List(s"$name (missing)")
                ZIO.foreachDiscard(chain(iface))(c => Console.printLine(s"    -> $c").orDie)
            yield ()
    end match
  end showOne

end AttrInspect
