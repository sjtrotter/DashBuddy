package cloud.trotter.dashbuddy.core.pipeline.rules

import java.security.MessageDigest
import java.util.Locale

/**
 * The ONE sha256 helper for the rule engine (#362). Used for privacy hashing
 * (customer names/addresses via the `sha256` transform) and offer identity.
 *
 * FAIL CLOSED: returns null on digest failure. The old duplicated copies
 * returned the un-hashed input — a privacy hash whose failure mode is the
 * plaintext. Callers either tolerate null (parsed fields) or substitute a
 * non-reversible fallback; none may ever see the input echoed back.
 */
internal fun sha256OrNull(input: String): String? = try {
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
} catch (_: Exception) {
    null
}
