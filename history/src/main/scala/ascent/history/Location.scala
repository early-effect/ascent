package ascent.history

import ascent.squawk.Eq

/** Same-document URL (path + query + hash). No origin: a [[History]] session never crosses sites.
  *
  * Punctuation is not stored. `search` is the query without `?`, `hash` without `#`. An empty pathname becomes `/`.
  * [[Eq]] is structural so an equal write is a Squawk no-op (`popstate` + `hashchange` of the same place notify once).
  * That does **not** suppress `push`: same URL, new stack entry, no observer fire.
  *
  * This type stays URL-only. Route params, `history.state`, and matchers belong above History (a later
  * `ascent.router`), not here.
  */
final case class Location private (pathname: String, search: String, hash: String) derives Eq:

  /** Path + optional `?search` + optional `#hash`, suitable for `pushState` / `<a href>`. */
  def href: String =
    val q = if search.isEmpty then "" else s"?$search"
    val h = if hash.isEmpty then "" else s"#$hash"
    s"$pathname$q$h"

  /** First-wins query lookup. Missing key is `None`. Values are not URL-decoded. */
  def param(name: String): Option[String] =
    Location.queryPairs(search).collectFirst { case (k, v) if k == name => v }

  /** Resolve a same-document relative spec against this location.
    *
    *   - `"#/active"` keeps pathname and search
    *   - `"?scene=empty"` keeps pathname; takes search (and hash if the string has one)
    *   - a path replaces pathname and takes any `?` / `#` suffix
    */
  def resolve(rel: String): Location =
    val s = rel.trim
    if s.isEmpty then this
    else if s.startsWith("#") then copy(hash = Location.stripPrefix(s, '#'))
    else if s.startsWith("?") then
      val rest      = s.tail
      val (q, hash) = Location.splitOnce(rest, '#')
      copy(search = q, hash = hash)
    else Location.parse(s)

end Location

object Location:

  val root: Location = Location("/", "", "")

  /** Normalize pathname / search / hash (strip `?` / `#`, empty path becomes `/`). */
  def apply(pathname: String, search: String, hash: String): Location =
    new Location(
      pathname = normalizePath(pathname),
      search = stripPrefix(search, '?'),
      hash = stripPrefix(hash, '#'),
    )

  /** Parse a path / query / hash spec. `"#/active"` is a hash-only location at `/`. Empty is [[root]]. */
  def parse(raw: String): Location =
    val s = raw.trim
    if s.isEmpty then root
    else
      val (pathQuery, hash) =
        if s.startsWith("#") then ("", s.tail)
        else splitOnce(s, '#')
      val (path, search) =
        if pathQuery.startsWith("?") then ("", pathQuery.tail)
        else splitOnce(pathQuery, '?')
      Location(path, search, hash)
  end parse

  private def normalizePath(p: String): String =
    if p.isEmpty then "/"
    else if p.startsWith("/") then p
    else s"/$p"

  private def stripPrefix(s: String, c: Char): String =
    if s.nonEmpty && s.charAt(0) == c then s.substring(1) else s

  private def splitOnce(s: String, c: Char): (String, String) =
    val i = s.indexOf(c)
    if i < 0 then (s, "")
    else (s.substring(0, i), s.substring(i + 1))

  private def queryPairs(search: String): List[(String, String)] =
    if search.isEmpty then Nil
    else
      search
        .split("&")
        .iterator
        .filter(_.nonEmpty)
        .map { part =>
          val i = part.indexOf('=')
          if i < 0 then (part, "")
          else (part.substring(0, i), part.substring(i + 1))
        }
        .toList
end Location
