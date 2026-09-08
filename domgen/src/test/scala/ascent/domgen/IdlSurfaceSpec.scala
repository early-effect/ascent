package ascent.domgen

import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Collections, unnamed specials, and generics: parse, build, render, and a census over the real webref snapshot. */
object IdlSurfaceSpec extends ZIOSpecDefault:

  private def parseMembers(members: String) =
    Webref
      .parseIdl(s"""{"idlparsed":{"idlNames":{"X":{"type":"interface","name":"X","members":[$members]}}}}""")
      .map(_.interfaces("X"))

  private def defsFor(iface: Webref.IdlInterface, extras: Webref.IdlInterface*) =
    val idl = Webref.Idl((iface +: extras).map(i => i.name -> i).toMap)
    DefBuilder.interfaceDefs(idl, skipNames = Set.empty).find(_.name == iface.name).get

  private def opIface(idlType: String, known: Webref.IdlInterface*) =
    defsFor(
      Webref.IdlInterface("X", None, Nil, operations = List(Webref.IdlOperation("op", idlType, Nil))),
      known*
    )

  private def snapshotFiles: Task[List[String]] =
    ZIO.attemptBlocking {
      Files
        .list(Paths.get("data/webref/idlparsed"))
        .iterator()
        .asScala
        .toList
        .filter(_.toString.endsWith(".json"))
        .map(Files.readString(_))
    }

  private def str(json: Json): Option[String] = json match
    case Json.Str(s) => Some(s)
    case _           => None

  private def walk(json: Json)(pf: Json => Set[String]): Set[String] = json match
    case Json.Obj(fields) => pf(json) ++ fields.map(_._2).flatMap(walk(_)(pf)).toSet
    case Json.Arr(els)    => els.flatMap(walk(_)(pf)).toSet
    case _                => Set.empty

  def spec = suite("IDL surface")(
    suite("parse")(
      test("single-arg generics decode as generic<T>") {
        checkAll(Gen.fromIterable(List("sequence", "Promise", "FrozenArray", "ObservableArray"))) { g =>
          for x <- parseMembers(
              s"""{"type":"attribute","name":"val","idlType":{"generic":"$g","union":false,"idlType":[{"idlType":"DOMString"}]}}"""
            ).orDie
          yield assertTrue(x.attributes.head.idlType == s"$g<DOMString>")
        }
      },
      test("record<K, V> keeps both arguments") {
        for x <- parseMembers(
            """{"type":"attribute","name":"val","idlType":{"generic":"record","union":false,"idlType":[{"idlType":"DOMString"},{"idlType":"long"}]}}"""
          )
        yield assertTrue(x.attributes.head.idlType == "record<DOMString, long>")
      },
      test("iterable, maplike, setlike, and async_iterable decode onto the interface") {
        for
          it   <- parseMembers("""{"type":"iterable","idlType":[{"idlType":"Node"}]}""")
          pair <- parseMembers(
            """{"type":"iterable","idlType":[{"idlType":"USVString"},{"idlType":"FormDataEntryValue"}]}"""
          )
          ml <- parseMembers(
            """{"type":"maplike","readonly":true,"idlType":[{"idlType":"DOMString"},{"idlType":"Highlight"}]}"""
          )
          sl <- parseMembers("""{"type":"setlike","readonly":false,"idlType":[{"idlType":"XRPlane"}]}""")
          ai <- parseMembers(
            """{"type":"async_iterable","idlType":[{"idlType":"any"}],"arguments":[{"type":"argument","name":"options","optional":true,"idlType":{"idlType":"Opts"}}]}"""
          )
        yield assertTrue(
          it.iterable.contains(Webref.IdlIterable("Node")),
          pair.iterable.contains(Webref.IdlIterable("FormDataEntryValue", Some("USVString"))),
          ml.maplike.contains(Webref.IdlMaplike("DOMString", "Highlight", readonly = true)),
          sl.setlike.contains(Webref.IdlSetlike("XRPlane", readonly = false)),
          ai.asyncIterable.get.valueType == "any",
          ai.asyncIterable.get.iteratorParams.map(_.name) == List("options"),
        )
      },
      test("unnamed specials synthesize apply / update / delete / toString") {
        val cases = List("getter" -> "apply", "setter" -> "update", "deleter" -> "delete", "stringifier" -> "toString")
        checkAll(Gen.fromIterable(cases)) { (special, name) =>
          val args =
            if special == "setter" then
              """[{"type":"argument","name":"index","idlType":{"idlType":"unsigned long"}},{"type":"argument","name":"val","idlType":{"idlType":"DOMString"}}]"""
            else if special == "stringifier" then "[]"
            else """[{"type":"argument","name":"index","idlType":{"idlType":"unsigned long"}}]"""
          for x <- parseMembers(
              s"""{"type":"operation","name":"","special":"$special","idlType":{"idlType":"DOMString"},"arguments":$args}"""
            ).orDie
          yield assertTrue(x.operations.head.name == name, x.operations.head.special == special)
        }
      },
      test("a named getter keeps its IDL name") {
        for x <- parseMembers(
            """{"type":"operation","name":"item","special":"getter","idlType":{"idlType":"Node"},"arguments":[{"type":"argument","name":"index","idlType":{"idlType":"unsigned long"}}]}"""
          )
        yield assertTrue(x.operations.head.name == "item")
      },
    ),
    suite("build")(
      test("writable maplike synthesizes size, get/has/set, and jsIterator") {
        val def_ = defsFor(
          Webref.IdlInterface(
            "HighlightRegistry",
            None,
            Nil,
            maplike = Some(Webref.IdlMaplike("DOMString", "Highlight", readonly = false)),
          ),
          Webref.IdlInterface("Highlight", None, Nil),
        )
        val get = def_.methods.find(_.scalaName == "get").get
        assertTrue(
          def_.attributes.exists(a => a.name == "size" && a.scalaType == "Int"),
          def_.methods.map(_.scalaName).toSet
            == Set("get", "has", "keys", "values", "entries", "forEach", "set", "delete", "clear", "jsIterator"),
          get.returnType == "scala.scalajs.js.UndefOr[Highlight]",
          def_.methods.exists(m => m.scalaName == "jsIterator" && m.jsSymbol.contains("iterator")),
        )
      },
      test("readonly maplike omits mutators") {
        val methods = defsFor(
          Webref.IdlInterface(
            "RTCStatsReport",
            None,
            Nil,
            maplike = Some(Webref.IdlMaplike("DOMString", "object", readonly = true)),
          )
        ).methods.map(_.scalaName).toSet
        assertTrue(
          methods.contains("get"),
          methods.contains("has"),
          !methods.contains("set"),
          !methods.contains("clear"),
        )
      },
      test("iterable<T> binds @@iterator; the structural catalog does NOT") {
        val iface      = Webref.IdlInterface("NodeList", None, Nil, iterable = Some(Webref.IdlIterable("Node")))
        val node       = Webref.IdlInterface("Node", None, Nil)
        val js         = defsFor(iface, node)
        val structural = DefBuilder
          .interfaceDefs(
            Webref.Idl(Map("NodeList" -> iface, "Node" -> node)),
            skipNames = Set.empty,
            typeOf = (t, i) => DefBuilder.structuralType(t, Set("Node", "NodeList"), i),
            jsNative = false,
          )
          .find(_.name == "NodeList")
          .get
        val it = js.methods.find(_.scalaName == "jsIterator").get
        assertTrue(
          it.jsSymbol.contains("iterator"),
          it.returnType == "scala.scalajs.js.Iterator[Node]",
          structural.methods.forall(_.scalaName != "jsIterator"),
        )
      },
      test("unnamed getter is apply with bracketAccess; unnamed deleter is a delete method") {
        val getter = defsFor(
          Webref.IdlInterface(
            "X",
            None,
            Nil,
            operations = List(
              Webref.IdlOperation(
                "apply",
                "DOMString",
                List(Webref.IdlParam("index", "unsigned long", false)),
                "getter",
              )
            ),
          )
        ).methods.head
        val deleter = defsFor(
          Webref.IdlInterface(
            "Y",
            None,
            Nil,
            operations = List(
              Webref.IdlOperation("delete", "undefined", List(Webref.IdlParam("key", "DOMString", false)), "deleter")
            ),
          )
        ).methods.head
        assertTrue(getter.scalaName == "apply", getter.bracketAccess, deleter.scalaName == "delete")
      },
      test("Promise / sequence / FrozenArray / record map to js.Promise / js.Array / js.Dictionary") {
        val cases = List(
          ("Promise<Blob>", "scala.scalajs.js.Promise[Blob]"),
          ("sequence<DOMString>", "scala.scalajs.js.Array[String]"),
          ("FrozenArray<DOMString>", "scala.scalajs.js.Array[String]"),
          ("ObservableArray<DOMString>", "scala.scalajs.js.Array[String]"),
          ("record<DOMString, long>", "scala.scalajs.js.Dictionary[Int]"),
        )
        checkAll(Gen.fromIterable(cases)) { (idl, scala) =>
          assertTrue(opIface(idl, Webref.IdlInterface("Blob", None, Nil)).methods.head.returnType == scala)
        }
      },
      test("async_iterable binds @@asyncIterator") {
        val it = defsFor(
          Webref.IdlInterface(
            "ReadableStream",
            None,
            Nil,
            asyncIterable = Some(
              Webref.IdlIterable(
                "any",
                iteratorParams = List(Webref.IdlParam("options", "ReadableStreamIteratorOptions", optional = true)),
              )
            ),
          )
        ).methods.head
        assertTrue(
          it.scalaName == "jsAsyncIterator",
          it.jsSymbol.contains("asyncIterator"),
          it.params.map(_.name) == List("options"),
        )
      },
    ),
    suite("render")(
      test("jsMixins land on the extends clause") {
        val src = Renderer.interfaces(
          List(
            InterfaceDef(
              "NodeList",
              None,
              List(FacadeMember("length", "Int")),
              Nil,
              jsMixins = List("js.Iterable[Node]"),
            )
          )
        )
        assertTrue(src.contains("class NodeList extends js.Object with js.Iterable[Node]:"))
      },
      test("@JSBracketAccess wraps apply; @JSName wraps @@asyncIterator") {
        val src = Renderer.interfaces(
          List(
            InterfaceDef(
              "X",
              None,
              Nil,
              List(
                MethodDef("apply", "apply", "String", List(ParamDef("index", "Int")), bracketAccess = true),
                MethodDef("jsAsyncIterator", "jsAsyncIterator", "js.Object", Nil, jsSymbol = Some("asyncIterator")),
              ),
            )
          )
        )
        assertTrue(
          src.contains("import scala.scalajs.js.annotation.{JSGlobal, JSBracketAccess, JSName}"),
          src.contains("@JSBracketAccess"),
          src.contains("def apply(index: Int): String = js.native"),
          src.contains("@JSName(js.Symbol.asyncIterator)"),
          src.contains("def jsAsyncIterator(): js.Object = js.native"),
        )
      },
    ),
    suite("census (vendored snapshot)")(
      test("every member type, special, and generic in the snapshot is modelled") {
        for files <- snapshotFiles
        yield
          val roots = files.map(_.fromJson[Json]).collect { case Right(j) => j }
          val kinds = roots.flatMap { root =>
            val parsed = root match
              case Json.Obj(f) => f.toMap.get("idlparsed")
              case _           => None
            parsed.toList.flatMap { p =>
              walk(p) {
                case Json.Obj(fields) =>
                  fields.toMap.get("members") match
                    case Some(Json.Arr(els)) =>
                      els.flatMap {
                        case Json.Obj(mf) => mf.toMap.get("type").flatMap(str)
                        case _            => None
                      }.toSet
                    case _ => Set.empty
                case _ => Set.empty
              }
            }
          }.toSet
          val specials = roots
            .flatMap(walk(_) {
              case Json.Obj(fields) => fields.toMap.get("special").flatMap(str).toSet
              case _                => Set.empty
            })
            .toSet
          val generics = roots
            .flatMap(walk(_) {
              case Json.Obj(fields) => fields.toMap.get("generic").flatMap(str).filter(_.nonEmpty).toSet
              case _                => Set.empty
            })
            .toSet
          assertTrue(
            files.nonEmpty,
            (kinds -- Webref.modelledMemberKinds).isEmpty,
            (specials -- Webref.modelledSpecials).isEmpty,
            (generics -- Webref.modelledGenerics).isEmpty,
          )
      }
    ),
  )
end IdlSurfaceSpec
