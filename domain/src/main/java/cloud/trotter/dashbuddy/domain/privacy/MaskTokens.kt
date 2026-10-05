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

    private const val HASHED_MASK_LENGTH = 15 // "[redacted:" + 4 hex + "]"

    /**
     * #919 (Astra review P1): [value] ENDS with a mask token — the plain [REDACTED] or the hashed
     * `[redacted:<4hex>]`. This is the already-masked skip the whole-node UNKNOWN scrubs use: a rule's own
     * output is either the bare mask or a kept prefix FOLLOWED by the mask ("For [redacted:ab12]"), so the
     * token sits at the end — while a value that merely CONTAINS the prefix ("[redacted] Riley S wants…", a
     * literal the dasher typed) is NOT masked and must still be scrubbed. Substring presence is not proof.
     */
    fun endsWithMask(value: String): Boolean {
        if (value.endsWith(REDACTED)) return true
        if (value.length < HASHED_MASK_LENGTH) return false
        val tail = value.substring(value.length - HASHED_MASK_LENGTH)
        if (!tail.startsWith("$REDACTED_PREFIX:") || !tail.endsWith("]")) return false
        val hex = tail.substring(REDACTED_PREFIX.length + 1, HASHED_MASK_LENGTH - 1)
        return hex.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
