package ascent.datastar

import zio.json.ast.Json
import zio.test.*

/** RFC 7386 merge semantics — pure, the prime negative-case target. */
object JsonMergeSpec extends ZIOSpecDefault:

  private def obj(fields: (String, Json)*): Json.Obj = Json.Obj(zio.Chunk.fromIterable(fields))

  def spec = suite("JsonMerge")(
    test("recursive object merge keeps untouched members") {
      val target = obj("a" -> Json.Num(1), "b" -> Json.Num(2))
      val patch  = obj("b" -> Json.Num(20), "c" -> Json.Num(3))
      val merged = JsonMerge.merge(target, patch)
      assertTrue(merged == obj("a" -> Json.Num(1), "b" -> Json.Num(20), "c" -> Json.Num(3)))
    },
    test("a null member deletes that key") {
      val target = obj("a" -> Json.Num(1), "b" -> Json.Num(2))
      val patch  = obj("b" -> Json.Null)
      assertTrue(JsonMerge.merge(target, patch) == obj("a" -> Json.Num(1)))
    },
    test("nested objects merge recursively") {
      val target = obj("user" -> obj("name" -> Json.Str("a"), "age" -> Json.Num(1)))
      val patch  = obj("user" -> obj("age" -> Json.Num(2)))
      assertTrue(JsonMerge.merge(target, patch) == obj("user" -> obj("name" -> Json.Str("a"), "age" -> Json.Num(2))))
    },
    test("a non-object patch replaces the target wholesale") {
      val target = obj("a" -> Json.Num(1))
      assertTrue(JsonMerge.merge(target, Json.Str("x")) == Json.Str("x"))
    },
    test("merging onto a non-object target starts fresh from the patch members") {
      assertTrue(JsonMerge.merge(Json.Num(5), obj("a" -> Json.Num(1))) == obj("a" -> Json.Num(1)))
    },
    test("deleting an absent key is a no-op") {
      val target = obj("a" -> Json.Num(1))
      assertTrue(JsonMerge.merge(target, obj("z" -> Json.Null)) == obj("a" -> Json.Num(1)))
    },
  )
end JsonMergeSpec
