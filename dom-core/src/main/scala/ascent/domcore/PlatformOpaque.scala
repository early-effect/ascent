package ascent.domcore

/** The escape-hatch marker type for a structural member whose real WebIDL type isn't part of the portable structural
  * surface — a rendering-only API (Canvas 2D context, WebGL), a callback, a dictionary, a union/sequence, or any other
  * shape the platform-neutral catalog doesn't model (see [[ascent.domgen.DefBuilder.structuralType]], the generator's
  * type mapper that decides when a member's type resolves to this).
  *
  * A member typed `PlatformOpaque` compiles on every platform but has no cross-platform value — the generated in-memory
  * implementation ([[ascent.domcore.generated]]) implements such a member's `def` directly as `???` (throws
  * `NotImplementedError` only if actually called), never a fabricated instance of this type. `PlatformOpaque` itself
  * carries no members and is never constructed — it exists purely so the generated trait catalog's method signatures
  * type-check identically on jvm/js/native.
  */
sealed trait PlatformOpaque
