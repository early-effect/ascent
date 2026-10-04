package ascent.domcore

import ascent.domcore.generated.{DocumentType, DocumentTypeMemory}

/** `DocumentType` is a node, not three fields on every [[NodeMemoryBase]]. `name` is the node's `nodeName`. `publicId`
  * and `systemId` live here. An HTML doctype is `name = "html"` with both ids empty.
  *
  * `nodeType` is 10 (`DOCUMENT_TYPE_NODE`). A plain [[ascent.domcore.generated.DocumentMemory]] has no doctype child.
  */
trait DocumentTypeOverrides:
  self: NodeMemoryBase & DocumentType =>

  private[domcore] var publicIdRef: String = ""
  private[domcore] var systemIdRef: String = ""

  override def nodeType: Int = 10

  def name: String     = self.nodeNameRef
  def publicId: String = publicIdRef
  def systemId: String = systemIdRef
end DocumentTypeOverrides

object DocumentTypeOverrides:
  /** An HTML doctype node: `<!DOCTYPE html>`. */
  def html: DocumentType = create("html", "", "")

  def create(name: String, publicId: String, systemId: String): DocumentType =
    val dt = new DocumentTypeMemory
    dt.nodeTypeRef = 10
    dt.nodeNameRef = name
    dt.publicIdRef = publicId
    dt.systemIdRef = systemId
    dt
end DocumentTypeOverrides
