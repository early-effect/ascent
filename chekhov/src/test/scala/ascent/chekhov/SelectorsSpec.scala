package ascent.chekhov

import zio.test.*

/** Browser-free: CSS fragments compiled from the lattice. */
object SelectorsSpec extends ZIOSpecDefault:

  def spec = suite("Selectors")(
    test("taggedTestId prefixes the catalog tag") {
      assertTrue(
        taggedSelector(TagHandle.button.tagName, "inc") == """button[data-testid="inc"]""",
        taggedSelector(TagHandle.input.tagName, "todo") == """input[data-testid="todo"]""",
        taggedSelector(TagHandle.textarea.tagName, "bio") == """textarea[data-testid="bio"]""",
        taggedSelector(TagHandle.select.tagName, "pick") == """select[data-testid="pick"]""",
      )
    },
    test("placeholderSelector quotes the hint") {
      assertTrue(
        placeholderSelector("input", "What needs to be done?") ==
          """input[placeholder="What needs to be done?"]"""
      )
    },
    test("escapeAttr escapes quotes and backslashes") {
      assertTrue(
        testIdSelector("""a"b\c""") == """[data-testid="a\"b\\c"]""",
        taggedSelector("button", """say "hi"""") == """button[data-testid="say \"hi\""]""",
      )
    },
    test("TagHandle givens wrap the matching handle class") {
      val backend = RecordingBackend()
      val input   = TagHandle.input.wrap("input[data-testid=\"x\"]", backend)
      val button  = TagHandle.button.wrap("button[data-testid=\"x\"]", backend)
      val area    = TagHandle.textarea.wrap("textarea[data-testid=\"x\"]", backend)
      val select  = TagHandle.select.wrap("select[data-testid=\"x\"]", backend)
      assertTrue(
        input.isInstanceOf[InputHandle[?]],
        button.isInstanceOf[ButtonHandle[?]],
        area.isInstanceOf[TextAreaHandle[?]],
        select.isInstanceOf[SelectHandle[?]],
        !button.isInstanceOf[InputHandle[?]],
      )
    },
  )

  /** No-op backend so wrap can be exercised without a Page. */
  final class RecordingBackend extends HandleBackend[[A] =>> A]:
    def click(selector: String)(using zio.Trace): Unit               = ()
    def fill(selector: String, value: String)(using zio.Trace): Unit = ()
    def press(selector: String, key: String)(using zio.Trace): Unit  = ()
    def innerText(selector: String)(using zio.Trace): String         = ""
    def textContent(selector: String)(using zio.Trace): String       = ""
end SelectorsSpec
