class HelloSpec extends munit.FunSuite:
  test("greeting is non-empty"):
    assert(Hello.greeting.nonEmpty)
