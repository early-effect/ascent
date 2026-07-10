package ascent.css

/** Anything that can render as part of a CSS rule body — a single declaration, a nested selector, an `@media` block, or
  * `@keyframes`. The composition primitive used throughout the CSS DSL.
  *
  * Forward-compat note: `Declaration(name: String, value: String)` is the universal leaf form. Hand-written property
  * objects in [[Styles]] produce these, and a future code generator (parsing `@webref/css`) will produce **the same
  * shape**, so [[CssClass]] never has to know who built its declarations.
  */
sealed trait CssMember:
  /** Render this member as the body lines of a CSS rule. Selectors render as full rules with trailing newlines;
    * declarations render as bare `name: value;` (no indent).
    */
  def render: String

  /** [[Keyframes]] this member references by name (e.g. an `animation:` declaration built via [[Keyframes.use]]).
    * [[CssClass]] reads this transitively so a class pulls the `@keyframes` blocks it animates into its own style
    * contribution — the dependency is captured as DATA, not as the construction side effect of touching the object.
    * Empty by default; leaves that reference keyframes and containers that nest such leaves override it.
    */
  def referencedKeyframes: Seq[Keyframes] = Nil
end CssMember

/** A leaf CSS declaration: `name: value;` (or `name: value !important;`).
  *
  * `bang` is the `!important` cascade modifier. It applies to ANY declaration regardless of value grammar, so it lives
  * here on the leaf rather than on each typed property — get it from any builder result via [[important]]:
  * `S.transitionDuration.s(0.01).important`.
  *
  * `keyframe` records a [[Keyframes]] this declaration animates, set by [[Keyframes.use]] so the class carrying it
  * collects the `@keyframes` block. It never affects rendering (the name is already in `value`); it's a pure dependency
  * edge, so a plain `Declaration(name, value)` is unchanged.
  */
final case class Declaration(name: String, value: String, bang: Boolean = false, keyframe: Option[Keyframes] = None)
    extends CssMember:
  def render: String = if bang then s"$name: $value !important;" else s"$name: $value;"

  override def referencedKeyframes: Seq[Keyframes] = keyframe.toSeq

  /** This declaration marked `!important`. */
  def important: Declaration = copy(bang = true)

/** A selector with its own declarations / nested members. Nested selectors render as separate rules with the parent
  * prepended — `Selector(".foo")(Selector(" .bar")(d))` produces `.foo .bar { d }`.
  *
  * The `selector` string carries its own leading combinator/separator (`" .bar"` = descendant, `":hover"` =
  * pseudo-attached, `" li"` = descendant element); it concatenates directly onto the parent path.
  *
  * **Typed selectors.** The [[Sel]] surface ([[Selector.apply(sel:ascent\.css\.Sel*]] /
  * [[Selector.apply(parts:ascent\.css\.Sel*]] / the `Selector.cls`/`pseudoClass`/… factories) is the spelling to prefer
  * — `Selector(Cls(Card), PseudoClass.hover)(decls*)` instead of `Selector(".card:hover", decls*)`. The raw `String`
  * constructor stays as an escape hatch for grammars the typed surface doesn't model.
  */
final case class Selector(selector: String, members: CssMember*) extends CssMember:
  def render: String =
    val builder = StringBuilder()
    renderTo(builder, "", "")
    builder.result()

  override def referencedKeyframes: Seq[Keyframes] = members.flatMap(_.referencedKeyframes)

  /** Recursively render this selector and all nested selectors as separate top-level rules, each prefixed with the
    * accumulated parent selector path. `indent` accumulates as we descend into at-rule blocks (`@media`, `@supports`,
    * …) so nested rules stay readable.
    *
    * Selector-list (`,`) aware: if THIS selector is a comma list and there's a non-empty parent, the parent is
    * prepended to EACH segment (CSS has no distributive nesting at the string level), so `Selector(a.or(b))` nested
    * under `.card` renders `.card a, .card b` — not the invalid `.card a, b`.
    */
  private[css] def renderTo(builder: StringBuilder, parent: String, indent: String): Unit =
    val full =
      if parent.nonEmpty && selector.contains(",") then selector.split(",").map(seg => parent + seg.trim).mkString(", ")
      else parent + selector
    CssMember.renderBody(builder, full, members, indent)
end Selector

object Selector:
  /** A typed selector rule: `Selector(Cls(Card).pseudoClass(PseudoClass.hover), decls*)`. The [[Sel]] renders with no
    * leading combinator, so it composes onto the parent path exactly like the string form.
    */
  def apply(sel: Sel, members: CssMember*): Selector = new Selector(sel.rendered, members*)

  /** A COMPOSED typed selector — compounds (concatenates, no combinator) all `parts` into one selector, then takes the
    * rule body: `Selector(Cls(Card), PseudoClass.hover)(decls*)` → `.card:hover { … }`. Use the combinator methods on
    * [[Sel]] (`a.child(b)`) when you need a combinator BETWEEN parts; this curried form is for the common compound case
    * (and matches the requested `Selector(Cls(x), PseudoClass.hover)(args*)` shape).
    */
  def apply(part: Sel, parts: Sel*)(members: CssMember*): Selector =
    val compound = (part +: parts).map(_.rendered).mkString
    new Selector(compound, members*)

  /** Class-selector rule: `Selector.cls(Card)(decls*)` → `.card { … }`. */
  def cls(name: String)(members: CssMember*): Selector = Selector(Sel.cls(name), members*)

  /** Class-selector rule from a [[CssClass]] (reads `.className`): `Selector.cls(Footer.Bar)(decls*)`. */
  def cls(c: CssClass)(members: CssMember*): Selector = Selector(Sel.cls(c), members*)

  /** Id-selector rule: `Selector.id("main")(decls*)` → `#main { … }`. */
  def id(name: String)(members: CssMember*): Selector = Selector(Sel.id(name), members*)

  /** Type/element-selector rule: `Selector.tag("li")(decls*)` → `li { … }`. */
  def tag(name: String)(members: CssMember*): Selector = Selector(Sel.tag(name), members*)

  /** Pseudo-class rule (attached to the ancestor): `Selector.pseudoClass(PseudoClass.hover)(decls*)` → `:hover { … }`
    * (renders `‹parent›:hover` when nested). The requested `Selector.pseudoClass(PseudoClass.nthChild(n))(arg, rest*)`
    * shape works because `nthChild` returns a [[PseudoClass]].
    */
  def pseudoClass(pc: PseudoClass)(members: CssMember*): Selector = Selector(pc, members*)

  /** Pseudo-element rule: `Selector.pseudoElement(PseudoElement.before)(decls*)` → `::before { … }`. */
  def pseudoElement(pe: PseudoElement)(members: CssMember*): Selector = Selector(pe, members*)
end Selector

/** A CSS at-rule that wraps a block of inner rules under a condition (e.g. `@media`, `@container`, `@supports`). Unlike
  * a [[Selector]], an at-rule is NOT concatenated with its ancestor selector — it wraps the SAME parent selector path's
  * rules in a conditional block. Inner declarations attach to the ancestor selector; inner Selectors append to it as
  * usual; nested AtRuleBlocks wrap further.
  *
  * Subclasses pin the at-rule prefix (`@media `, `@container `, `@container my-card `, `@supports `) — everything else
  * is shared.
  */
sealed trait AtRuleBlock extends CssMember:
  /** The full at-rule prefix INCLUDING any name + the parens-wrapped query, ending with a trailing space — what gets
    * written before the `{`. e.g. `@media (max-width: 600px)`, `@container my-card (min-width: 400px)`.
    */
  protected def atRuleHeader: String

  /** Inner members the block wraps. */
  def members: Seq[CssMember]

  override def referencedKeyframes: Seq[Keyframes] = members.flatMap(_.referencedKeyframes)

  def render: String =
    val builder = StringBuilder()
    renderTo(builder, "", "")
    builder.result()

  private[css] def renderTo(builder: StringBuilder, parent: String, indent: String): Unit =
    builder.append(indent).append(atRuleHeader).append(" {\n")
    CssMember.renderBody(builder, parent, members, indent + "  ")
    builder.append(indent).append("}\n")
end AtRuleBlock

object MediaQuery:
  /** Apply a typed [[MediaCondition]] (built from the generated [[Media]] feature catalog) to a block of inner rules:
    * `MediaQuery(Media.prefersReducedMotion.reduce, ...)`.
    */
  def apply(condition: MediaCondition, members: CssMember*): MediaQuery =
    new MediaQuery(condition.render, members*)

/** `@media (q) { <rules> }`. The `query` is the part inside the parens, e.g. `(max-width: 600px)`. The typed entry
  * point is [[Media]]; the `String` constructor is a forward-compat escape hatch for grammars the typed surface doesn't
  * model yet.
  */
final case class MediaQuery(query: String, members: CssMember*) extends AtRuleBlock:
  protected def atRuleHeader: String = s"@media $query"

object ContainerQuery:
  /** Apply a typed [[ContainerCondition]] to a block of inner rules: `ContainerQuery(Container.minWidth.px(400), ...)`.
    */
  def apply(condition: ContainerCondition, members: CssMember*): ContainerQuery =
    new ContainerQuery(name = "", query = condition.render, members = members)

  /** Apply a typed [[ContainerCondition]] AGAINST A NAMED ANCESTOR container, naming it with a typed [[ContainerName]]
    * — the SAME value passed to the `container-name` declaration, so the two sites can't drift:
    * `ContainerQuery.named(Card, Container.maxWidth.px(420), …)`.
    */
  def named(name: ContainerName, condition: ContainerCondition, members: CssMember*): ContainerQuery =
    new ContainerQuery(name = name.render, query = condition.render, members = members)

  /** String escape hatch for the named form — prefer the [[ContainerName]] overload so the name is shared with its
    * `container-name` declaration.
    */
  def named(name: String, condition: ContainerCondition, members: CssMember*): ContainerQuery =
    new ContainerQuery(name = name, query = condition.render, members = members)

  /** String escape hatch: hand-roll a query (e.g. CSS containers level 4 style queries `style(--theme: dark)` that the
    * typed feature surface doesn't model yet).
    */
  def apply(query: String, members: CssMember*): ContainerQuery =
    new ContainerQuery(name = "", query = query, members = members)
end ContainerQuery

/** `@container <name>? <query> { <rules> }`. The optional `name` selects a specific ancestor by `container-name`; with
  * no name, the closest container of any name is matched.
  */
final case class ContainerQuery(
    name: String,
    query: String,
    members: Seq[CssMember],
) extends AtRuleBlock:
  protected def atRuleHeader: String =
    if name.isEmpty then s"@container $query"
    else s"@container $name $query"

object SupportsQuery:
  /** Apply a typed [[SupportsCondition]] to a block of inner rules:
    * `SupportsQuery(Supports.declaration(Styles.display.flex), ...)`.
    */
  def apply(condition: SupportsCondition, members: CssMember*): SupportsQuery =
    new SupportsQuery(condition.render, members*)

/** `@supports <query> { <rules> }`. The typed entry point is [[Supports]]; the `String` constructor is a forward-compat
  * escape hatch for `<supports-condition>` shapes the typed surface doesn't model yet.
  */
final case class SupportsQuery(query: String, members: CssMember*) extends AtRuleBlock:
  protected def atRuleHeader: String = s"@supports $query"

object CssMember:
  /** Shared body renderer used by both Selector and AtRuleBlock subclasses: given a parent selector path, a list of
    * members, and an indent prefix, emit the inline rule (declarations under `parent`) followed by each nested Selector
    * / AtRuleBlock. Selectors append to `parent`; AtRuleBlocks inherit `parent` unchanged but wrap their body in a new
    * at-rule block.
    */
  private[css] def renderBody(
      builder: StringBuilder,
      parent: String,
      members: Seq[CssMember],
      indent: String,
  ): Unit =
    val declarations = members.collect { case d: Declaration => d }
    if declarations.nonEmpty && parent.nonEmpty then
      builder.append(indent).append(parent).append(" {\n")
      declarations.foreach { d =>
        builder.append(indent).append("  ").append(d.render).append("\n")
      }
      builder.append(indent).append("}\n")
    members.foreach {
      case _: Declaration => () // already emitted in the inline rule above
      case s: Selector    => s.renderTo(builder, parent, indent)
      case b: AtRuleBlock => b.renderTo(builder, parent, indent)
    }
  end renderBody
end CssMember
