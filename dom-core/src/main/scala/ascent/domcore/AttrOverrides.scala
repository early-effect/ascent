package ascent.domcore

import ascent.domcore.generated.Attr

/** Real `name` for [[ascent.domcore.generated.Attr]]. `Attr.name` is `readonly` per WebIDL, so the reflection generator
  * would emit a fixed `def name = ""`; here it reads [[NodeMemoryBase.nodeNameRef]] instead, which [[NamedNodeMapView]]
  * stamps when it synthesizes an `Attr` for a stored (name, value) pair — so a round-trip through `getNamedItem`
  * reports the attribute's actual name.
  */
trait AttrOverrides:
  self: NodeMemoryBase & Attr =>

  def name: String = self.nodeNameRef
