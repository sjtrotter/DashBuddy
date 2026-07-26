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
 * factory has run. [PRESENTATION_HASH] is its coarser sibling (#859), resolved
 * from [ParsedFields.presentationHash]: the identity of one *showing* of the
 * surface rather than of its current content, so a live-re-quoting card
 * (Uber's offer, which re-renders pay/miles/minutes every few seconds) fires a
 * per-presentation effect ONCE instead of once per quote.
 *
 * Both are deterministic across runs and replay (String/Double hashCode are
 * specified), so recovery dedupe keys match the live run's. Both are DERIVED —
 * never raw parse fields — which is why [RESERVED_FIELD_NAMES] exists: the
 * dedupeKey template lint skips them as a class rather than as one-offs.
 */
object DedupeTokens {

    /** Resolves to `ParsedFields.dedupeHash()` of the observation's typed parse. */
    const val PARSED_HASH = "{parsedHash}"

    /** Resolves to `ParsedFields.presentationHash()` of the observation's typed parse (#859). */
    const val PRESENTATION_HASH = "{presentationHash}"

    /**
     * Token → resolver over the typed parse. The single enumeration every other
     * member derives from (names, the fast-path scan, the replacement loop) —
     * adding a token here is the whole change.
     */
    private val RESOLVERS: Map<String, (ParsedFields) -> Int> = mapOf(
        PARSED_HASH to { parsed -> parsed.dedupeHash() },
        PRESENTATION_HASH to { parsed -> parsed.presentationHash() },
    )

    /** Names treated as reserved (never raw parse fields) — lint/tooling SSOT. */
    val RESERVED_FIELD_NAMES: Set<String> =
        RESOLVERS.keys.mapTo(mutableSetOf()) { it.removeSurrounding("{", "}") }

    /**
     * Resolve reserved tokens in [effects]' dedupe keys against [parsed].
     * No-op (same list instance) when nothing references a token.
     */
    fun resolve(effects: List<RequestedEffect>, parsed: ParsedFields): List<RequestedEffect> {
        if (effects.none { effect -> effect.dedupeKey?.let(::referencesToken) == true }) return effects
        return effects.map { effect ->
            val key = effect.dedupeKey
            if (key == null || !referencesToken(key)) {
                effect
            } else {
                var resolved: String = key
                for ((token, hashOf) in RESOLVERS) {
                    if (resolved.contains(token)) {
                        resolved = resolved.replace(token, hashOf(parsed).toString())
                    }
                }
                effect.copy(dedupeKey = resolved)
            }
        }
    }

    private fun referencesToken(key: String): Boolean =
        RESOLVERS.keys.any { token -> key.contains(token) }
}
