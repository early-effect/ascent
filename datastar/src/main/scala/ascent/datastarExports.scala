package ascent

/** Export facade for `ascent-datastar`. Contributes to the OPEN `package ascent` so a plain `import ascent.*` exposes
  * the datastar protocol surface (the signal store, the dialect, and the neutral patch model).
  */
export ascent.datastar.{SignalStore, SignalPatch, RemoteEvent, RemoteDialect, Datastar, ElementPatchMode}
