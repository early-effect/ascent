package ascent.domgen

import zio.*
import zio.test.*

import scala.io.Source

object WebrefParseSpec extends ZIOSpecDefault:

  private def resource(name: String): String =
    val src = Source.fromInputStream(getClass.getResourceAsStream(s"/webref/$name"), "UTF-8")
    try src.mkString
    finally src.close()

  def spec = suite("Webref parsing (typed zio-json)")(
    suite("elements/html.json")(
      test("parses the element list with name + interface") {
        for parsed <- Webref.parseElements(resource("elements-html.json"))
        yield
          val byName = parsed.map(e => e.name -> e.interface).toMap
          assertTrue(
            parsed.size == 4,
            byName("div") == "HTMLDivElement",
            byName("input") == "HTMLInputElement",
            byName("br") == "HTMLBRElement",
          )
      },
      test("fails with a typed WebrefParseError (not a defect) on malformed JSON") {
        for exit <- Webref.parseElements("{ not json").exit
        yield assert(exit)(Assertion.failsWithA[WebrefParseError])
      },
    ),
    suite("events.json")(
      test("parses each event's type and interface") {
        for evs <- Webref.parseEvents(resource("events.json"))
        yield
          val byType = evs.map(e => e.`type` -> e.interface).toMap
          assertTrue(
            byType("click") == "PointerEvent",
            byType("input") == "InputEvent",
            byType("keydown") == "KeyboardEvent",
            byType("abort") == "Event",
          )
      },
      test("captures bubbles when present on a target, defaulting to None when absent") {
        for evs <- Webref.parseEvents(resource("events.json"))
        yield
          val byType = evs.map(e => e.`type` -> e).toMap
          assertTrue(
            byType("click").targets.head.bubbles == Some(true),
            byType("abort").targets.head.bubbles == None,
          )
      },
    ),
    suite("idlparsed/*.json")(
      test("exposes interfaces by name with their inheritance parent") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield assertTrue(
          idl.interfaces("HTMLInputElement").inheritance == Some("HTMLElement"),
          idl.interfaces("HTMLElement").inheritance == Some("Element"),
          idl.interfaces("Element").inheritance == Some("Node"),
        )
      },
      test("attribute members are surfaced with their IDL type") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val attrs = idl.interfaces("HTMLInputElement").attributes.map(a => a.name -> a.idlType).toMap
          assertTrue(
            attrs.keySet == Set("type", "value", "required", "checked"),
            attrs("type") == "DOMString",
            attrs("required") == "boolean",
          )
      },
      test("an attribute carrying [Reflect] in extAttrs is marked reflected; one without it is not") {
        // The in-memory DOM generator uses this signal to auto-implement reflected properties with
        // zero hand code, while non-reflected ones (genuine internal state) need real logic.
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val byName = idl.interfaces("HTMLInputElement").attributes.map(a => a.name -> a.reflected).toMap
          assertTrue(
            byName("type") == true,
            byName("checked") == false,
            byName("value") == false,
          )
      },
      test("an attribute with OTHER extAttrs but no [Reflect] entry is not falsely marked reflected") {
        // `required` carries [CEReactions] but no [Reflect] — a non-empty extAttrs must not false-positive.
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val byName = idl.interfaces("HTMLInputElement").attributes.map(a => a.name -> a.reflected).toMap
          assertTrue(byName("required") == false)
      },
      test("an attribute with a missing extAttrs field defaults to reflected = false, not a decode failure") {
        // The decoder must tolerate a totally absent extAttrs field — it defaults to Nil on RawMember.
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "attribute", "name": "bare", "idlType": { "idlType": "DOMString" } }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").attributes.head.reflected == false)
      },
      test("a union-typed attribute decodes to its member names joined by ' | ', not collapsed to a placeholder") {
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "attribute", "name": "val", "idlType": {
              "type": "attribute-type", "union": true,
              "idlType": [ { "idlType": "TrustedType" }, { "idlType": "DOMString" } ]
            } }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").attributes.head.idlType == "TrustedType | DOMString")
      },
      test("a union-typed operation parameter decodes the same way as a union-typed attribute") {
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "operation", "name": "op", "idlType": { "idlType": "undefined" }, "arguments": [
              { "type": "argument", "name": "options", "idlType": {
                "type": "argument-type", "union": true,
                "idlType": [ { "idlType": "AddEventListenerOptions" }, { "idlType": "boolean" } ]
              } }
            ] }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").operations.head.params.head.idlType == "AddEventListenerOptions | boolean")
      },
      test("a THREE-member union preserves every member in declaration order") {
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "attribute", "name": "val", "idlType": {
              "type": "attribute-type", "union": true,
              "idlType": [ { "idlType": "A" }, { "idlType": "B" }, { "idlType": "C" } ]
            } }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").attributes.head.idlType == "A | B | C")
      },
      test(
        "a `sequence<T>` generic (array-shaped idlType, but union: false) is NOT mistaken for a union — decodes as sequence<T>"
      ) {
        // A sequence's idlType payload is array-shaped just like a union's; only the `union: false` flag
        // distinguishes them. An earlier union-splitting change ignored the flag and collapsed
        // sequence<DOMString> to plain "DOMString".
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "operation", "name": "getNames", "idlType": {
              "type": "return-type", "generic": "sequence", "union": false,
              "idlType": [ { "idlType": "DOMString" } ]
            }, "arguments": [] }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").operations.head.returnType == "sequence<DOMString>")
      },
      test("a `record<K, V>` generic (two-argument, unsupported) falls through to None, not a bogus sequence") {
        // sequence<T> handling accepts a single type argument only; a two-arg generic must not be
        // misread as sequence<T> with just the first arg.
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "attribute", "name": "val", "idlType": {
              "type": "attribute-type", "generic": "record", "union": false,
              "idlType": [ { "idlType": "DOMString" }, { "idlType": "DOMString" } ]
            } }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").attributes.head.idlType == "any")
      },
      test(
        "a malformed union (idlType array entries missing their own idlType field) still fails closed to None, not a decode crash"
      ) {
        // `union: true` but the entries don't match UnionMemberShape: simpleIdlType must fall through to
        // None (dropped upstream via `getOrElse("any")`), never throw.
        for idl <- Webref.parseIdl("""{
          "idlparsed": { "idlNames": { "X": { "type": "interface", "name": "X", "members": [
            { "type": "attribute", "name": "val", "idlType": {
              "type": "attribute-type", "union": true, "idlType": [ 1, 2 ]
            } }
          ] } } }
        }""")
        yield assertTrue(idl.interfaces("X").attributes.head.idlType == "any")
      },
      test("operation members are surfaced with their return type and ordered argument list") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val canvasOps = idl.interfaces("HTMLCanvasElement").operations.map(o => o.name -> o).toMap
          val getCtx    = canvasOps("getContext")
          assertTrue(
            canvasOps.keySet == Set("getContext"),
            getCtx.returnType == "RenderingContext",
            getCtx.params.map(_.name) == List("contextId"),
            getCtx.params.map(_.idlType) == List("DOMString"),
          )
      },
      test("operation arguments preserve order and capture the optional flag") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val fillRect = idl.interfaces("CanvasRenderingContext2D").operations.find(_.name == "fillRect").get
          assertTrue(
            fillRect.params.map(_.name) == List("x", "y", "w", "h"),
            fillRect.params.forall(_.idlType == "unrestricted double"),
          )
      },
      test("top-level `callback` blocks (e.g. FrameRequestCallback) are surfaced as IdlCallback entries") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val frame = idl.callbacks.find(_.name == "FrameRequestCallback").get
          assertTrue(
            frame.returnType == "undefined",
            frame.params.map(_.name) == List("time"),
            frame.params.map(_.idlType) == List("double"),
          )
      },
      test("dictionary blocks are surfaced with fields, types, required flag, and inheritance") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val opts = idl.dictionaries.find(_.name == "AddEventListenerOptions").get
          assertTrue(
            opts.inheritance == Some("EventListenerOptions"),
            opts.fields.map(_.name).toSet == Set("passive", "once", "signal"),
            opts.fields.map(_.idlType).toSet == Set("boolean", "AbortSignal"),
            opts.fields.forall(!_.required),
          )
      },
      test("enum blocks are surfaced with their string values") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val mode = idl.enums.find(_.name == "ShadowRootMode").get
          assertTrue(mode.values == List("open", "closed"))
      },
      test("`callback interface` blocks (EventListener) are also captured as IdlCallback") {
        // EventListener is a callback interface with one op `handleEvent`; we unwrap it to the same
        // shape as a bare callback (name + return type + params).
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val listener = idl.callbacks.find(_.name == "EventListener").get
          assertTrue(
            listener.returnType == "undefined",
            listener.params.map(_.name) == List("event"),
            listener.params.map(_.idlType) == List("Event"),
          )
      },
      test("a mixin's operations are surfaced too (focus / blur on HTMLOrSVGOrMathMLElement)") {
        for idl <- Webref.parseIdl(resource("idlparsed-html.json"))
        yield
          val mixin = idl.interfaces("HTMLOrSVGOrMathMLElement")
          assertTrue(
            mixin.isMixin == true,
            mixin.operations.map(_.name).toSet == Set("focus", "blur"),
          )
      },
      test("an interface with no inheritance parses as None (e.g. Event root)") {
        for idl <- Webref.parseIdl(resource("idlparsed-uievents.json"))
        yield assertTrue(idl.interfaces("Event").inheritance == None)
      },
    ),
  )
end WebrefParseSpec
