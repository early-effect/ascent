package ascent

/** Export facade for `ascent-history` (optional URL session bound to Squawk).
  *
  * Contributes to the OPEN `package ascent` (see [[ascent.exports]] in dom-types). `History.browser` is Scala.js-only.
  * Matching, layouts, and `Link` belong in a later `ascent.router`, not this primitive.
  */
export ascent.history.{History, Location}
