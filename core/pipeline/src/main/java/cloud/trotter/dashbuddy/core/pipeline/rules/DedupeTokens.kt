package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.state.ParsedFields

/**
 * Reserved dedupeKey tokens resolved AFTER typed parsing (#427).
 *
 * `{field}` templates resolve at match time against the branch's RAW parse
 * fields ([Ruleset.matchFirst]) — but identity fields computed by
 * [ParsedFieldsFactory] (the offer hash, derived from pay/distance/stores)
 * don't exist in the raw map, so a key like `offer-ss-{offerHash}` stayed a
 * literal constant: every offer shared one `effects_fired` row and one
 * throttle bucket, and the second offer inside the 60s window was silently
 * swallowed.
 *
 * [PARSED_HASH] is the shape-agnostic fix: the classifier resolves it from
 * [ParsedFields.dedupeHash] — the typed parse's content identity — once the
 * factory has run. Deterministic across runs and replay (String/Double
 * hashCode are specified), so recovery dedupe keys match the live run's.
 */
object DedupeTokens {

    /** Resolves to `ParsedFields.dedupeHash()` of the observation's typed parse. */
    const val PARSED_HASH = "{parsedHash}"

    /** Names treated as reserved (never raw parse fields) — lint/tooling SSOT. */
    val RESERVED_FIELD_NAMES = setOf("parsedHash")

    /**
     * Resolve reserved tokens in [effects]' dedupe keys against [parsed].
     * No-op (same list instance) when nothing references a token.
     */
    fun resolve(effects: List<RequestedEffect>, parsed: ParsedFields): List<RequestedEffect> {
        if (effects.none { it.dedupeKey?.contains(PARSED_HASH) == true }) return effects
        val hash = parsed.dedupeHash().toString()
        return effects.map { effect ->
            val key = effect.dedupeKey
            if (key != null && key.contains(PARSED_HASH)) {
                effect.copy(dedupeKey = key.replace(PARSED_HASH, hash))
            } else {
                effect
            }
        }
    }
}
