package ascent.css

/** A typed, composable CSS *value* — a color, length, gradient, shadow, … — that renders to the CSS source string a
  * property accepts (no property name, no trailing `;`).
  *
  * Mirrors [[MediaCondition]]'s shape: an extensible value type whose single contract is `render`. The point of a
  * common base is the attach mechanism — `StylesFoundation.DS` carries ONE `apply(CssValue)` overload, so every
  * property accepts every typed value with no per-property wiring (`background(gradient)`, `boxShadow(shadow)`,
  * `color(c)` all just work). Values render to a `String` at the leaf, so [[Declaration]] stays the universal
  * `name: value` string form and the `DS.apply(String)` escape hatch survives for grammars no value type models yet.
  */
trait CssValue:
  /** The CSS source for this value — the right-hand side of a `name: value` declaration. */
  def render: String
