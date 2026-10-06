package ascent.docs

/** Per-artifact release numbers the docs are allowed to print. `released` is what Central has today. */
final case class ModuleVersions(
    core: String,
    js: String,
    css: String,
    conduit: String,
    history: String,
    element: String,
    mcpApp: String,
    mcpHost: String,
    html: String,
    datastar: String,
    datastarJs: String,
    datastarHttp: String,
    preview: String,
    chekhov: String,
    dom: String,
    mountEngine: String,
)

object ModuleVersions:
  def released: ModuleVersions =
    ModuleVersions(
      core = Released.core,
      js = Released.js,
      css = Released.css,
      conduit = Released.conduitBridge,
      history = Released.history,
      element = Released.element,
      mcpApp = Released.mcpApp,
      mcpHost = Released.mcpHost,
      html = Released.html,
      datastar = Released.datastar,
      datastarJs = Released.datastarJs,
      datastarHttp = Released.datastarHttp,
      preview = Released.preview,
      chekhov = Released.ascentChekhov,
      dom = Released.dom,
      mountEngine = Released.mountEngine,
    )
end ModuleVersions
