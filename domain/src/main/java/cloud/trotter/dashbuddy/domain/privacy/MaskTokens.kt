package cloud.trotter.dashbuddy.domain.privacy

/**
 * The runtime mask token's ONE owner (#1160 review AJ5): every mask the rule redact side emits starts with
 * [REDACTED_PREFIX] — the plain `[redacted]` ([REDACTED]) and the hashed `[redacted:<4hex>]`. The runtime
 * already-masked skip (`CustomerTextMarkers`), the redact output (`CompiledRedact.REDACTED`), the census mask
 * step (`PiiShapes.MASK_LITERALS`) and the corpus tests' mask regex all derive from it.
 */
object MaskTokens {
    const val REDACTED_PREFIX: String = "[redacted"

    /** The plain (hash-less) mask. */
    const val REDACTED: String = "$REDACTED_PREFIX]"
}
