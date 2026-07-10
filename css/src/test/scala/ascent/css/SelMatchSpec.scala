package ascent.css

import zio.test.*

/** Matcher correctness: [[Sel.parse]] + `.matches` against a pure in-memory tree fixture (not a real DOM). Nodes are
  * distinguished by IDENTITY, not structural equality, so sibling `<li>`s with identical tag/attrs stay
  * distinguishable.
  */
object SelMatchSpec extends ZIOSpecDefault:

  final case class Node(tag: String, cls: Set[String] = Set.empty, attrs: Map[String, String] = Map.empty)

  final case class Tree(root: Elem)
  final case class Elem(node: Node, children: List[Elem] = Nil)

  private def elem(tag: String, cls: Set[String] = Set.empty, attrs: Map[String, String] = Map.empty)(
      children: Elem*
  ): Elem = Elem(Node(tag, cls, attrs), children.toList)

  // page = <div class="page"><ul><li class="a" data-x="1">A</li><li class="b">B</li><li class="c">C</li></ul></div>
  private val liA   = elem("li", cls = Set("a"), attrs = Map("data-x" -> "1"))()
  private val liB   = elem("li", cls = Set("b"))()
  private val liC   = elem("li", cls = Set("c"))()
  private val ul    = elem("ul")(liA, liB, liC)
  private val page  = elem("div", cls = Set("page"))(ul)
  private val empty = elem("span")()

  private def findParent(root: Elem, target: Elem): Option[Elem] =
    if root.children.exists(_ eq target) then Some(root)
    else root.children.iterator.map(findParent(_, target)).find(_.isDefined).flatten

  given Matchable[Elem] with
    def tagName(e: Elem): String                    = e.node.tag
    def attr(e: Elem, name: String): Option[String] =
      if name == "class" then Some(e.node.cls.mkString(" ")).filter(_.nonEmpty) else e.node.attrs.get(name)
    def classes(e: Elem): Set[String]        = e.node.cls
    def id(e: Elem): Option[String]          = e.node.attrs.get("id")
    def parent(e: Elem): Option[Elem]        = findParent(page, e)
    def children(e: Elem): Seq[Elem]         = e.children
    def previousSiblings(e: Elem): Seq[Elem] =
      parent(e).map(p => p.children.takeWhile(!_.eq(e))).getOrElse(Nil)
    def nextSiblings(e: Elem): Seq[Elem] =
      parent(e).map(p => p.children.dropWhile(!_.eq(e)).drop(1)).getOrElse(Nil)
  end given

  private def matches(selector: String, e: Elem): Boolean =
    Sel.parse(selector).fold(err => throw new RuntimeException(err), _.matches(e))

  def spec = suite("SelMatch (via Sel.parse + .matches)")(
    suite("simple selectors")(
      test("type selector matches the tag") {
        assertTrue(matches("li", liA), !matches("div", liA))
      },
      test("universal selector matches anything") {
        assertTrue(matches("*", liA), matches("*", page))
      },
      test("class selector matches a present class, not an absent one") {
        assertTrue(matches(".a", liA), !matches(".b", liA))
      },
      test("id selector matches via the id attribute") {
        val withId = elem("li", attrs = Map("id" -> "main"))()
        assertTrue(matches("#main", withId), !matches("#other", withId))
      },
      test("a compound requires ALL simples to match") {
        assertTrue(matches("li.a", liA), !matches("li.b", liA))
      },
    ),
    suite("attribute selectors — all six operators")(
      test("presence, exact, prefix, suffix, substring, includes, dash-match") {
        val e = elem("a", attrs = Map("href" -> "https://example.com/page.pdf", "rel" -> "nofollow noopener"))()
        assertTrue(
          matches("[href]", e),
          matches("""a[href="https://example.com/page.pdf"]""", e),
          matches("""a[href^="https://"]""", e),
          matches("""a[href$=".pdf"]""", e),
          matches("""a[href*="example"]""", e),
          matches("""a[rel~="nofollow"]""", e),
          !matches("[missing]", e),
        )
      },
      test("dash-match matches exact or prefix-dash (language subtags)") {
        val en    = elem("p", attrs = Map("lang" -> "en"))()
        val enUS  = elem("p", attrs = Map("lang" -> "en-US"))()
        val enemy = elem("p", attrs = Map("lang" -> "enemy"))()
        assertTrue(
          matches("""[lang|="en"]""", en),
          matches("""[lang|="en"]""", enUS),
          !matches("""[lang|="en"]""", enemy),
        )
      },
    ),
    suite("combinators")(
      test("descendant matches an ancestor at any depth") {
        assertTrue(matches("div li", liA), matches(".page .a", liA))
      },
      test("child matches only the DIRECT parent") {
        assertTrue(matches("ul > li", liA), !matches("div > li", liA))
      },
      test("next-sibling matches only the IMMEDIATELY preceding sibling") {
        assertTrue(matches("li.a + li", liB), !matches("li.a + li", liC))
      },
      test("subsequent-sibling matches ANY earlier sibling") {
        assertTrue(matches("li.a ~ li", liB), matches("li.a ~ li", liC), !matches("li.c ~ li", liA))
      },
      test("a 3-level descendant chain") {
        assertTrue(matches("div ul li", liA))
      },
      test("mixed child + descendant") {
        assertTrue(matches("div ul > li", liA), matches("div > ul li", liA))
      },
      test("column combinator never matches — documented simplification, not a fabricated approximation") {
        assertTrue(!matches("col || td", liA))
      },
    ),
    suite("structural pseudo-classes")(
      test(":first-child / :last-child / :only-child") {
        assertTrue(
          matches("li:first-child", liA),
          !matches("li:first-child", liB),
          matches("li:last-child", liC),
          !matches("li:last-child", liA),
          !matches("li:only-child", liA),
        )
      },
      test(":empty matches a childless element") {
        assertTrue(matches(":empty", empty), !matches(":empty", ul))
      },
      test(":root matches only the parentless element") {
        assertTrue(matches(":root", page), !matches(":root", ul))
      },
      test(":nth-child covers odd/even/an+b/single-index") {
        assertTrue(
          matches("li:nth-child(1)", liA),
          matches("li:nth-child(odd)", liA),
          matches("li:nth-child(odd)", liC),
          !matches("li:nth-child(odd)", liB),
          matches("li:nth-child(2n)", liB),
          matches("li:nth-child(-n+2)", liA),
          matches("li:nth-child(-n+2)", liB),
          !matches("li:nth-child(-n+2)", liC),
        )
      },
      test(":not() negates a selector list") {
        assertTrue(matches("li:not(.b)", liA), !matches("li:not(.a)", liA), !matches("li:not(.a, .b)", liB))
      },
      test(":is() / :where() match-any") {
        assertTrue(matches("li:is(.a, .c)", liA), matches("li:where(.a, .c)", liC), !matches("li:is(.a, .c)", liB))
      },
      test(":has() matches if a descendant satisfies the argument") {
        assertTrue(matches("ul:has(.b)", ul), !matches("ul:has(.zzz)", ul))
      },
    ),
    suite("selector lists")(
      test("comma-list matches if ANY alternative matches") {
        assertTrue(matches("div, li.a", liA), matches("span, li.zzz, li.a", liA), !matches("div, li.zzz", liA))
      }
    ),
    suite("interaction-state pseudo-classes — always match, per the documented policy")(
      test("a:hover matches the same elements as the plain type selector") {
        assertTrue(matches("li:hover", liA), matches("li:hover", liB))
      },
      test("an unrecognized pseudo-class (lang/dir/state) also always matches — structural completeness") {
        assertTrue(matches("li:lang(en)", liA), matches("li:dir(rtl)", liA), matches("li:state(checked)", liA))
      },
    ),
    suite("Sel.matches misuse — programmer error, not a silent always-false")(
      test("calling .matches on a hand-built (non-parsed) Sel throws immediately") {
        val handBuilt = Cls("row")
        assertTrue(
          try
            handBuilt.matches(liA)
            false
          catch case _: IllegalArgumentException => true
        )
      }
    ),
  )
end SelMatchSpec
