package ascent

/** Export facade for `ascent-conduit` (the optional conduit ↔ Squawk bridge).
  *
  * Contributes to the OPEN `package ascent` (see [[ascent.exports]] in dom-types). View code already takes a
  * conduit-free `Ctx[M]` handle, so the only conduit name a non-model file names is `Ctx` itself (plus the `ctx`
  * extension at the bootstrap/wiring layer). Both come through `import ascent.*`.
  */

export ascent.conduit.Ctx
export ascent.conduit.ctx
