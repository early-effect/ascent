import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

import sbt.*
import sbt.Keys.*
import sbt.librarymanagement.ScalaModuleInfo
import zipx.plugin.PublishedModule
import zipx.plugin.ZipxPlugin.autoImport.PublishedRow

/** One Central release per Ship row, read from the publishing project's Maven metadata. */
object ReleasedLookup:

  def versions(extracted: Extracted, ships: Seq[PublishedRow]): Seq[(String, String)] =
    ReleasedGen.check()
    ships.map { row =>
      val ref = publishingRef(extracted, row)
      val (org, artifact) = coordinates(extracted, ref)
      val url = metadataUrl(org, artifact)
      val xml = fetch(url)
      val version = ReleasedGen.latestRelease(xml).getOrElse {
        sys.error(s"${row.identity}: $url has no major.minor.patch release")
      }
      (ReleasedGen.scalaName(row.identity), version)
    }

  private def publishingRef(extracted: Extracted, row: PublishedRow): ProjectRef =
    val candidates = row.memberRoots.flatMap { member =>
      val id = member: String
      List(s"${id}JVM", s"${id}JS", s"${id}Native", id)
    }
    candidates.iterator.map(id => ref(extracted, id)).collectFirst { case Some(found) => found }.getOrElse {
      sys.error(s"no project for ${row.identity}: tried ${candidates.mkString(", ")}")
    }

  private def ref(extracted: Extracted, id: String): Option[ProjectRef] =
    extracted.structure.allProjectRefs.find(_.project == id)

  private def coordinates(extracted: Extracted, ref: ProjectRef): (String, String) =
    val module = extracted.get(ref / projectID)
    val info: Option[ScalaModuleInfo] = extracted.getOpt(ref / scalaModuleInfo).flatten
    (module.organization, PublishedModule.artifactId(module, info))

  private def metadataUrl(organization: String, artifact: String): String =
    val group = organization.replace('.', '/')
    s"https://repo1.maven.org/maven2/$group/$artifact/maven-metadata.xml"

  private def fetch(url: String): String =
    val client = HttpClient.newBuilder().nn
      .connectTimeout(Duration.ofSeconds(15))
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
      .nn
    val request = HttpRequest.newBuilder(URI.create(url)).nn.timeout(Duration.ofSeconds(15)).GET().build().nn
    val response = client.send(request, HttpResponse.BodyHandlers.ofString()).nn
    response.statusCode match
      case 200 => response.body.nn
      case status =>
        sys.error(s"lookup $url: HTTP $status")
end ReleasedLookup
