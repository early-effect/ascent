sys.props.get("plugin.version") match
  case Some(v) => addSbtPlugin("rocks.earlyeffect" % "sbt-ascent-preview" % v)
  case _       => sys.error("plugin.version not set; pass it via scriptedLaunchOpts -Dplugin.version=...")
