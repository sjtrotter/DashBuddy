package cloud.trotter.dashbuddy.domain.util

import java.security.MessageDigest
import java.util.Locale

/**
 * The ONE sha256 helper (#362), hosted in `:domain` since #1093 so that a domain type
 * ([cloud.trotter.dashbuddy.domain.pipeline.NodeRef]) can hash without a second digest site;
 * `:core:pipeline`'s `sha256OrNull` delegates here.
 *
 * FAIL CLOSED: returns null on digest failure. The old duplicated copies returned the un-hashed
 * input — a privacy hash whose failure mode is the plaintext. Callers either tolerate null or
 * substitute a non-reversible fallback; none may ever see the input echoed back.
 */
fun sha256OrNull(input: String): String? = try {
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
} catch (_: Exception) {
    null
}
