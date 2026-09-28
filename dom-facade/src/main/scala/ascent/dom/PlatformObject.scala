package ascent.dom

import scala.scalajs.js

/** A WebIDL platform object: the root every generated interface and event facade extends. Its companion exports the
  * `Option` views of nullable members ([[NullableAccessors]]), which puts them in each facade type's implicit scope:
  * `frame.contentWindow` is an `Option[Window]` wherever `frame` is typed, under a bare `import ascent.dom`.
  */
@js.native
trait PlatformObject extends js.Object

object PlatformObject:
  export NullableAccessors.*
