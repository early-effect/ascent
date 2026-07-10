package ascent.ast

import scala.util.hashing.MurmurHash3

/** Structural id for an AST node — a 64-bit value derived purely from the tree shape.
  *
  * **Design properties** (pinned by [[AstIdSpec]]):
  *   - Pure function of structure: same value in → same id out, anywhere, always.
  *   - Reactive bindings contribute their *position*, not the underlying Squawk's identity or current value. So a
  *     `ReactiveText(s)` keeps the same id as `s` emits.
  *   - Static attribute *values* are state, not structure: changing a `class` attribute's value doesn't change the id.
  *     Adding/removing the attribute does.
  *   - Cross-platform (jvm/js/native): MurmurHash3 from the stdlib is consistent across all.
  *
  * **Collision philosophy**: a 64-bit hash collides every once in a galaxy. For 10k AST nodes the probability is around
  * 10^-12. Two strategies for handling that risk:
  *   - [[IdMode.Hash]] — accept the risk, no registry overhead. Two different ASTs that happen to hash-collide get the
  *     same id. Document, move on.
  *   - [[IdMode.HashWithRegistry]] — DEFAULT. Wrap the hash in an [[IdAssigner]] that tracks `hash → AST`. On
  *     collision, append a tiebreaker so the id stays unique within the assigner's lifetime. Tiny overhead.
  *
  * The pure structural hash lives here as `AstId.compute`; the registry layer lives in [[IdAssigner]].
  */
object AstId:

  /** A short numeric tag distinguishing each AST variant, mixed into the hash so two differently-shaped nodes can never
    * share an id even if their content fingerprints happen to coincide.
    */
  private val tagElement       = 0x01
  private val tagText          = 0x02
  private val tagEmpty         = 0x03
  private val tagFragment      = 0x04
  private val tagReactiveText  = 0x05
  private val tagReactiveChild = 0x06
  private val tagWhen          = 0x07
  private val tagForEach       = 0x08
  private val tagScoped        = 0x09
  private val tagForEachSignal = 0x0a
  private val tagServerRegion  = 0x0b

  /** Structural hash of a UI value. Lazy and cheap — recursion is bounded by tree depth, MurmurHash3 is a few cycles
    * per primitive. Each invocation does the same work; if you're inserting many ASTs into a Mount, prefer
    * [[IdAssigner]] which caches by node.
    */
  def compute(ui: UI[?]): Long = ui match
    case UI.Element(tag, attrs, children) =>
      val tagH      = MurmurHash3.stringHash(tag).toLong
      val attrsH    = hashAttrs(attrs)
      val childrenH = hashChildren(children)
      mix(tagElement.toLong, tagH, attrsH, childrenH)

    case UI.Text(value) =>
      mix(tagText.toLong, MurmurHash3.stringHash(value).toLong)

    case UI.Empty =>
      tagEmpty.toLong

    case UI.Fragment(children) =>
      mix(tagFragment.toLong, hashChildren(children))

    case UI.ReactiveText(_) =>
      // Reactive content is structurally just "there's a ReactiveText here." The squawk's
      // identity and current value deliberately do not contribute - same node across emits.
      tagReactiveText.toLong

    case UI.ReactiveChild(_) =>
      tagReactiveChild.toLong

    case UI.When(_, _) =>
      tagWhen.toLong

    case UI.ForEach(_, _, _) =>
      tagForEach.toLong

    case _: UI.ForEachSignal[?, ?] =>
      tagForEachSignal.toLong

    case UI.Scoped(_) =>
      // Like the other reactive boundaries, a Scoped node is structurally just "there's a
      // managed subtree here" — its builder closure's identity doesn't contribute.
      tagScoped.toLong

    case UI.ServerRegion(id, tag) =>
      // The region's own id + tag fully identify it (its interior is server-owned, not part of the
      // structural hash).
      mix(tagServerRegion.toLong, MurmurHash3.stringHash(id).toLong, MurmurHash3.stringHash(tag).toLong)

  /** Hash a sequence of children with each child's position mixed in, so two children of the same shape at different
    * positions get different contributions. Mirrors how MurmurHash3.orderedHash treats Seq order.
    */
  private def hashChildren(children: Vector[UI[?]]): Long =
    var acc = 0xcbf29ce484222325L // FNV-style seed; avalanched by the mix below
    var i   = 0
    while i < children.length do
      acc = mix(acc, i.toLong, compute(children(i)))
      i += 1
    acc

  /** Hash attributes by NAME ONLY. Static values, reactive values, and event handler values are state — they don't
    * change which node you're looking at. Adding or removing an attribute IS structural. Position within attrs doesn't
    * matter (attribute order is meaningless), so we sort the names for a canonical fingerprint.
    */
  private def hashAttrs(attrs: Vector[Attr[?]]): Long =
    val names = attrs.collect {
      case Attr.StaticAttr(name, _)   => name
      case Attr.ReactiveAttr(name, _) => name
      case Attr.EventHandler(ev, _)   => "on:" + ev            // events namespaced to avoid attr clashes
      case Attr.OnMount(_)            => "lifecycle:onmount"   // structural marker; presence/absence affects the id
      case Attr.OnUnmount(_)          => "lifecycle:onunmount" // ditto
      case Attr.OnMountScoped(_)      => "lifecycle:onmountscoped"
      case Attr.ReactiveClasses(_) => "class" // drives the `class` attribute, like a reactive class string
      // Attr.Style is deliberately EXCLUDED: it emits no DOM (it feeds the StyleRegistry), so it isn't part of node
      // identity. Excluding it also keeps a CssScope's target id stable — the scope's [data-ascent="<id>"] selector must
      // match an element whose id was computed WITHOUT the styles later applied to it.
    }.sorted
    val h = MurmurHash3.orderedHash(names.map(MurmurHash3.stringHash))
    h.toLong
  end hashAttrs

  /** Mix several Longs into one with good avalanche. Implementation: xor + splitmix64 step per input. Order-sensitive
    * (different argument order → different result), which we want because position matters in everything except
    * attribute names.
    */
  private def mix(values: Long*): Long =
    var z = 0xc3a5c85c97cb3127L // arbitrary non-zero seed
    var i = 0
    while i < values.length do
      z ^= values(i)
      // splitmix64 step - same one we used in the counter approach
      z = (z + 0x9e3779b97f4a7c15L) ^ ((z + 0x9e3779b97f4a7c15L) >>> 30)
      z = z * 0xbf58476d1ce4e5b9L
      z = z ^ (z >>> 27)
      z = z * 0x94d049bb133111ebL
      z = z ^ (z >>> 31)
      i += 1
    z
  end mix

  /** Render an id as a compact lowercase base36 string for use as an HTML attribute value. (`data-ascent="abc123"`).
    * Negative Longs produce a leading `-` from `toString`; we strip it so attribute values are pure alphanumerics.
    */
  def renderAttr(id: Long): String =
    java.lang.Long.toString(id, 36).stripPrefix("-")
end AstId
