package ascent.datastar

import ascent.squawk.Eq
import zio.*
import zio.json.{JsonDecoder, JsonEncoder}
import zio.json.ast.Json
import zio.test.*

object SignalStoreSpec extends ZIOSpecDefault:

  final case class Item(id: String, label: String) derives JsonDecoder, JsonEncoder, Eq

  private def put(name: String, v: Json, onlyIfMissing: Boolean = false) =
    SignalPatch.Put(name, v, onlyIfMissing)

  private def countFires[A](sqk: ascent.squawk.Squawk[A]): UIO[(Ref[Int], ascent.squawk.Subscription)] =
    for
      ref <- Ref.make(0)
      sub <- sqk.observe(_ => ref.update(_ + 1))
    yield (ref, sub)

  def spec = suite("SignalStore")(
    test("a declared signal seeds its init and a Put updates it") {
      for
        store <- SignalStore.make()
        count <- store.squawk("count", 0)
        seed  <- count.get
        _     <- store.route(put("count", Json.Num(5)))
        now   <- count.get
      yield assertTrue(seed == 0, now == 5)
    },
    test("a typed rich signal (List[Item]) decodes from a JSON array") {
      for
        store <- SignalStore.make()
        items <- store.squawk[List[Item]]("items", Nil)
        json = Json.Arr(zio.Chunk(Json.Obj(zio.Chunk("id" -> Json.Str("a"), "label" -> Json.Str("A")))))
        _   <- store.route(put("items", json))
        now <- items.get
      yield assertTrue(now == List(Item("a", "A")))
    },
    test("a Put for an unknown signal is a logged no-op (other signals untouched)") {
      for
        errs  <- Ref.make(Vector.empty[String])
        store <- SignalStore.make(msg => errs.update(_ :+ msg))
        count <- store.squawk("count", 1)
        _     <- store.route(put("ghost", Json.Num(99)))
        now   <- count.get
        log   <- errs.get
      yield assertTrue(now == 1, log.exists(_.contains("ghost")))
    },
    test("a decode failure retains the prior value and the store keeps working") {
      for
        errs  <- Ref.make(Vector.empty[String])
        store <- SignalStore.make(msg => errs.update(_ :+ msg))
        count <- store.squawk("count", 7)
        _     <- store.route(put("count", Json.Str("not-an-int")))
        bad   <- count.get
        _     <- store.route(put("count", Json.Num(8)))
        good  <- count.get
        log   <- errs.get
      yield assertTrue(bad == 7, good == 8, log.exists(_.contains("count")))
    },
    test("an Eq-equal write fires no observer") {
      for
        store        <- SignalStore.make()
        count        <- store.squawk("count", 3)
        (fires, sub) <- countFires(count)
        _            <- store.route(put("count", Json.Num(3)))
        n            <- fires.get
        _            <- sub.cancel
      yield assertTrue(n == 0)
    },
    test("a Delete (RFC-7386 null) resets the signal to its declared init") {
      for
        store   <- SignalStore.make()
        count   <- store.squawk("count", 42)
        _       <- store.route(put("count", Json.Num(100)))
        hundred <- count.get
        _       <- store.route(SignalPatch.Delete("count"))
        reset   <- count.get
      yield assertTrue(hundred == 100, reset == 42)
    },
    test("onlyIfMissing skips when a value already arrived, applies when missing") {
      for
        store  <- SignalStore.make()
        count  <- store.squawk("count", 0)
        _      <- store.route(put("count", Json.Num(5), onlyIfMissing = true)) // missing so far: applies
        first  <- count.get
        _      <- store.route(put("count", Json.Num(9), onlyIfMissing = true)) // now present: skipped
        second <- count.get
      yield assertTrue(first == 5, second == 5)
    },
    test("partial object merge updates only the changed field") {
      for
        store <- SignalStore.make()
        item  <- store.squawk("item", Item("a", "A"))
        _     <- store.route(put("item", Json.Obj(zio.Chunk("label" -> Json.Str("B")))))
        now   <- item.get
      yield assertTrue(now == Item("a", "B"))
    },
    test("flattening a multi-key signals object routes each name independently") {
      for
        store <- SignalStore.make()
        a     <- store.squawk("a", 0)
        b     <- store.squawk("b", 0)
        obj = Json.Obj(zio.Chunk("a" -> Json.Num(1), "b" -> Json.Num(2), "ghost" -> Json.Num(9)))
        _  <- store.routeAll(SignalPatch.fromSignals(obj, onlyIfMissing = false))
        av <- a.get
        bv <- b.get
      yield assertTrue(av == 1, bv == 2)
    },
    test("snapshot encodes all current signals as a JSON object") {
      for
        store <- SignalStore.make()
        _     <- store.squawk("count", 0)
        _     <- store.squawk("name", "x")
        _     <- store.route(put("count", Json.Num(5)))
        snap  <- store.snapshot
      yield assertTrue(
        snap.get("count").contains(Json.Num(5)),
        snap.get("name").contains(Json.Str("x")),
      )
    },
  )
end SignalStoreSpec
