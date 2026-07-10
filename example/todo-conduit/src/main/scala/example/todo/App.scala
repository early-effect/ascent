package example.todo

import ascent.*
import ascent.css.Styles.*
import ascent.dsl.*
import zio.*

/** Top-level TodoMVC layout: the page body, the centered shell, title, and the frosted-glass card wrapping header +
  * main + footer (the latter two only when there are todos).
  *
  * Owns the page-level styling: the [[Body]] backdrop, the [[Shell]]/[[Title]]/[[Glass]] surfaces, the title-glow
  * keyframe, and [[Chrome]] — the document-global at-rules (`@font-face`, `@page`, `@counter-style`, reduced-motion,
  * the no-backdrop-filter fallback, the focus ring, and the sr-only helper).
  *
  * The `when(todosNonEmpty)` boundary controls whether the list and footer are even mounted; emptying the list unmounts
  * those subtrees entirely, tearing down every observer they hold.
  */
object App:

  /** The page backdrop — layered radial accents over a dark diagonal base. Body-only, so it lives here, not in Theme.
    */
  private val pageGradient =
    Gradient.layers(
      Gradient.radial(RadialGeometry.circleAt(Position.at(20.pct, 0.pct)))(
        ColorStop(Theme.accent.alpha(0.18), Length.pct(0)),
        ColorStop(Color.transparent, Length.pct(45)),
      ),
      Gradient.radial(RadialGeometry.circleAt(Position.at(80.pct, 100.pct)))(
        ColorStop(Theme.cyan.alpha(0.15), Length.pct(0)),
        ColorStop(Color.transparent, Length.pct(50)),
      ),
      Gradient.linear(Angle.deg(160))(
        ColorStop(Color.hex("#0a0418"), Length.pct(0)),
        ColorStop(Color.hex("#1a1129"), Length.pct(60)),
        ColorStop(Color.hex("#08020e"), Length.pct(100)),
      ),
    )

  /** The page body itself — its backdrop, base font, and ink color. Applied to the root `E.body(...)`. */
  object Body
      extends CssClass(
        background(pageGradient),
        backgroundAttachment.fixed,
        color(Theme.onInk),
        // Font stacks have no typed value family — the raw string is the right escape hatch.
        fontFamily.of(
          FontFamily.named("Orbitron"),
          FontFamily.named("Inter"),
          FontFamily.systemUi,
          FontFamily.named("-apple-system"),
          FontFamily.sansSerif,
        ),
        minHeight.vh(100),
        margin.zero,
      )

  /** The centered column that holds the title + glass card. */
  object Shell
      extends CssClass(
        margin(48.px, Length.auto),
        maxWidth.px(580),
        padding(0, 16.px),
        Theme.FadeSlideIn.use(Time.s(0.5), TimingFunction.easeOut, fill = Some(SingleAnimationFillMode.Both)),
      )

  /** Slow drifting glow on the title. */
  object TitleGlow
      extends Keyframes(
        Frame.at(List("0%", "100%"))(
          textShadow(
            Shadow.list(
              Shadow(Length.zero, Length.zero, Length.px(24), Theme.accentGlow),
              Shadow(Length.zero, Length.zero, Length.px(48), Theme.accent.alpha(0.18)),
            )
          ),
          filter(Filter.brightness(1)),
        ),
        Frame.pct(50)(
          textShadow(
            Shadow.list(
              Shadow(Length.zero, Length.zero, Length.px(32), Theme.accentSoft),
              Shadow(Length.zero, Length.zero, Length.px(64), Theme.accent.alpha(0.32)),
            )
          ),
          filter(Filter.brightness(1.08)),
        ),
      )

  object Title
      extends CssClass(
        fontSize.px(76),
        fontWeight(200),
        textAlign.center,
        color(Theme.accent),
        margin(0, 0, 24.px, 0),
        letterSpacing.px(-2),
        textShadow(
          Shadow.list(
            Shadow(Length.zero, Length.zero, Length.px(24), Theme.accentGlow),
            Shadow(Length.zero, Length.zero, Length.px(48), Theme.accent.alpha(0.18)),
          )
        ),
        // `.use` references the keyframes AS DATA, so TitleGlow's @keyframes block rides along into this render's
        // stylesheet (vs the string form `animation(s"$TitleGlow …")`, which tracks no dependency).
        TitleGlow.use(Time.s(4), TimingFunction.easeInOut, iterations = Some(SingleAnimationIterationCount.Infinite)),
        // font-feature-settings is a `<feature-tag-value>#` string — the property's String escape hatch.
        fontFeatureSettings("'ss01' on"),
        MediaQuery(Media.maxWidth.px(520), fontSize.px(56), letterSpacing.px(-1)),
      )

  /** The frosted-glass blur, shared by the standard and `-webkit-` backdrop-filter declarations. */
  private val glassBlur = Filter.list(Filter.blur(Length.px(24)), Filter.saturate(1.4))

  /** A USER-DEFINED typed property: `-webkit-backdrop-filter` isn't in the webref `compat.json` set (the WHATWG
    * Compatibility Standard standardizes a fixed legacy `-webkit-` list that omits it), so we define it ourselves the
    * same way the generator does — `Prop` + the `Filterish` mixin. Proof the ADT system is open, not closed to webref.
    */
  object WebkitBackdropFilter extends Prop("-webkit-backdrop-filter") with Filterish

  /** The card's container name — declared once and shared by the `container-name` declaration and the `@container`
    * query that targets it, so the two can't drift out of sync.
    */
  private val cardContainer = ContainerName("todo-card")

  /** Frosted-glass surface that hosts the input + list + footer. */
  object Glass
      extends CssClass(
        background(Theme.glassFill),
        backdropFilter(glassBlur),
        WebkitBackdropFilter(glassBlur),
        border(Border.solid(1.px, Theme.glassBorder)),
        borderRadius.px(20),
        boxShadow(
          Shadow.list(
            Shadow(Length.zero, Length.px(24), Length.px(80), Color.rgba(0, 0, 0, 0.45)),
            Shadow(Length.zero, Length.px(4), Length.px(12), Theme.accent.alpha(0.12)),
            Shadow.inset(Length.zero, Length.px(1), Length.zero, Color.rgba(255, 255, 255, 0.04)),
          )
        ),
        overflow.hidden,
        // A container so the footer can lay out by the CARD's width, not the viewport's
        // (e.g. compact when the card is narrow inside a wide sidebar).
        containerType.inlineSize,
        // The typed ContainerName is shared with the @container query below — no repeated string literal.
        containerName(cardContainer),
        ContainerQuery.named(
          cardContainer,
          Container.maxWidth.px(420),
          Selector(
            Sel.descendant(Cls(Footer.Bar)),
            flexDirection.column,
            gap.px(8),
            alignItems.flexStart,
          ),
        ),
      )

  // --- document-global chrome (at-rules + rules not tied to one component) ---

  /** `@font-face` — load Orbitron display font for the title and inputs. */
  private val orbitronFontFace: FontFace = FontFace(
    Styles.fontFamily(FontFamily.named("Orbitron")),
    Styles.fontStyle.normal,
    Styles.fontWeight(400),
    FontFaceDescriptors.fontDisplay.swap,
    // Pinned gstatic woff2 — the version/hash rotates periodically, so this URL
    // eventually 404s and the font needs re-pinning from the Google Fonts CSS API.
    FontFaceDescriptors.src.url(
      "https://fonts.gstatic.com/s/orbitron/v35/yMJMMIlzdpvBhQQL_SC3X9yhF25-T1nyGy6BoWgzfDAlp7lk.woff2",
      format = "woff2",
    ),
  )

  /** `@page` — print stylesheet: drop the gradient for a clean letter-sized layout. */
  private val printPage: Page = Page(
    PageDescriptors.size.apply("letter"),
    PageDescriptors.pageOrientation.upright,
    Styles.margin(Length.in(0.75)),
  )

  /** `@counter-style` — diamond bullets for the filter list. */
  private val diamondCounter: CounterStyle = CounterStyle(
    "ascent-diamond",
    CounterStyleDescriptors.system.cyclic,
    CounterStyleDescriptors.symbols("\"◆\""),
    CounterStyleDescriptors.suffix("\" \""),
  )

  /** `@media (prefers-reduced-motion: reduce)` — honor the OS reduce-motion setting. */
  private val reducedMotionRule: MediaQuery = MediaQuery(
    Media.prefersReducedMotion.reduce,
    Selector(
      Sel.universal
        .or(Sel.universal.pseudoElement(PseudoElement.before))
        .or(Sel.universal.pseudoElement(PseudoElement.after)),
      animationDuration.ms(0.01).important,
      animationIterationCount(1).important,
      transitionDuration.ms(0.01).important,
    ),
  )

  /** `@supports (not (backdrop-filter: …))` — denser solid fill when blur is unsupported. */
  private val noBackdropFilterFallback: SupportsQuery = SupportsQuery(
    Supports.declaration(Styles.backdropFilter.apply("blur(1px)")).not,
    Selector(Cls(Glass), background(Color.rgba(38, 22, 64, 0.92))),
  )

  /** Document-global rules not tied to one component, built as typed [[Selector]]s and passed straight to
    * [[GlobalStyle]] (they lift via the [[Selector]]→[[GlobalRule]] conversion).
    */
  // The default browser outline would clash with the palette. `outline` is a `<line-width> || <line-style> ||
  // <color>` shorthand — a typed Border composite (Borderish), so the whole rule is type-checked.
  private val focusVisible = Selector(
    PseudoClass.focusVisible,
    outline(Border.solid(2.px, Theme.accent)),
    outlineOffset.px(2),
    borderRadius.px(4),
  )
  private val srOnly = Selector(
    Cls("sr-only"),
    position.absolute,
    width.px(1),
    height.px(1),
    padding.zero,
    margin.px(-1),
    overflow.hidden,
    // `clip` is the deprecated `rect()` form (Clip is the only property that uses this legacy shape) — modeled by
    // the typed Clip value rather than a raw string.
    clip(Clip.rect(0.px, 0.px, 0.px, 0.px)),
    whiteSpace.nowrap,
    border.zero,
  )

  /** Document-global styles; component `CssClass`es (including [[Body]]) self-register when a view uses them. Declared
    * on the root — passing it to `E.body(...)` runs the constructor that registers every block.
    */
  object Chrome
      extends GlobalStyle(
        focusVisible,
        srOnly,
        orbitronFontFace,
        printPage,
        diamondCounter,
        GlobalRule.atRule("media-reduced-motion", reducedMotionRule),
        GlobalRule.atRule("supports-no-backdrop-filter", noBackdropFilterFallback),
      )

  def component(ctx: Ctx[TodoApp.Model]) =
    for
      header <- Header.component(ctx)
      list   <- TodoList.component(ctx)
      footer <- Footer.component(ctx)
      todos  <- ctx.squawk(_.todos)
      hasTodos = todos.map(_.nonEmpty)
    yield E.body(
      Body,
      Chrome,
      // `/` focuses the new-todo input — unless the user is already typing in a field. Keyboard events bubble, and the
      // whole app lives inside this <body>, so a plain handler here catches every keydown; no document/window listener
      // needed, and the engine tears it down with the body. `.sync` so preventDefault fires during dispatch.
      Events.onKeyDown.sync { e =>
        val typing = e.targetTag.exists(t => t == "input" || t == "textarea")
        if e.key.contains("/") && !typing then
          e.preventDefaultNow()
          Dom.focusFirst(s".${Header.Input.className}")
      },
      E.div(
        Shell,
        Aria.role("region"),
        Aria.ariaLabel("Todo list app"),
        E.h1(Title, "todos"),
        E.div(
          Glass,
          header,
          when(hasTodos)(list),
          when(hasTodos)(footer),
        ),
      ),
    )
end App
