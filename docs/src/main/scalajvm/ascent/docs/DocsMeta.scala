package ascent.docs

import specular.site.ProjectMeta

/** Site identity written into chrome and `metadata.json`. Ascent is not one artifact, so `version` stays empty. The hub
  * card follows `docsUrl`. The header link follows `homepage`, which is the GitHub repo.
  */
object DocsMeta:
  val DocsUrl: String     = "https://www.earlyeffect.rocks/ascent/"
  val RepoUrl: String     = "https://github.com/early-effect/ascent"
  val Description: String =
    "Effect-native reactive UI for Scala 3; direct DOM, Squawk boundaries, ZIO throughout."

  def project: ProjectMeta =
    ProjectMeta(
      name = "ascent",
      organization = prop("organization").getOrElse("rocks.earlyeffect"),
      version = "",
      scalaVersion = prop("scalaVersion").getOrElse("3.9.0"),
      title = Some("ascent"),
      description = Some(Description),
      language = Some("Scala"),
      homepage = Some(prop("homepage").getOrElse(RepoUrl)),
      docsUrl = Some(DocsUrl),
    )

  private def prop(key: String): Option[String] =
    Option(System.getProperty(s"specular.meta.$key")).filter(_.nonEmpty)
end DocsMeta
