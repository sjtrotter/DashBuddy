package cloud.trotter.dashbuddy.core.pipeline.rules

import java.util.Locale

/**
 * Canonical customer-name key (#733) — the SSOT normalization applied BEFORE [sha256OrNull]
 * at every `customerNameHash` parse site (via the `normalizeCustomerName` transform) AND at the
 * matching capture mask (via a redact `normalize` flag, [CompiledRedactEntry.normalize]), so the
 * sha256 the parse persists and the `[redacted:<4hex>]` mask both derive from the SAME token — the
 * #623 mask↔hash invariant survives normalization.
 *
 * DoorDash renders one customer's name in several forms across surfaces: the pickup card and the
 * nav bottom-sheet show a short form ("Brandy S"), while the arrival / ID-check card shows a fuller
 * form ("Brandy Smith"). A raw `[trim, sha256]` chain therefore produced DIFFERENT hashes per
 * surface (#733 defect A) — the dropoff's `customerNameHash` matched NONE of its own pickups, so the
 * store-lineage join missed and the delivery landed with a NULL store. Canonicalizing to
 * `first-token + second-token initial` collapses those forms to one key:
 * "Brandy S", "Brandy S.", "Brandy Smith", "brandy  smith " → `brandy s`.
 *
 * Distinctness-preserving on already-masked tokens: `[redacted:ab12]` → `redactedab12` (a single
 * token — punctuation is stripped, no whitespace to split on), so two different customers still key
 * apart. A blank / punctuation-only value → `null` so the hash NEVER runs on an empty string
 * (fail-closed, matching the parse's null-tolerance).
 *
 * This is a platform-agnostic value transform (data in the rules + one implementation), never
 * platform-gated logic (#8). Rejected (#733): a first-token-only key unifies more forms but badly
 * degrades customer distinctness for dedup/correlation; the D6 join-miss WARN stays as the tripwire
 * if a first-name-only surface ever appears.
 */
private val WHITESPACE = Regex("\\s+")

internal fun customerNameKey(input: String): String? {
    val tokens = input
        .lowercase(Locale.ROOT)
        // Strip everything that isn't a letter, digit, or whitespace (kills "S." vs "S",
        // and collapses "[redacted:ab12]" to the single token "redactedab12").
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .trim()
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val first = tokens[0]
    val second = tokens.getOrNull(1)
    return if (second != null) "$first ${second.first()}" else first
}
