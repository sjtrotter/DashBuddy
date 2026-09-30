package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.ResourceIdGrammar
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes

/**
 * The id path's frame-FREE judgement (ADR-0011 §1; split from `SkeletonBuilder` by #1160 review AD10): may a
 * raw view id travel in the clear and key the fingerprint? The frame rule's per-frame containment on top of
 * it is `FrameFilter`'s.
 */
object IdPathJudgement {

    /**
     * May [id] travel in the clear and key the fingerprint (ADR-0011 §1)? The contract's static SHAPE
     * ([ResourceIdGrammar.isStaticShape], which the DTO and the server also enforce) AND — client-side,
     * because the predicates live in this module (review II3) — no customer-PII value predicate fires on
     * the id's NAME part with its separators (`_`, `.`, `-`, `:`) read as spaces, judged at EVERY token
     * start (so `row_Deliver_to_Sam` is caught by the "Deliver to " marker, `chip_Adam_S` by the name
     * shape). A bare name with no marker, lead-in or initial (`chip_Adam`, `Adam Smith`) has no shape a
     * frame-free predicate can tell from chrome (`chip_Gold`, `Artwork Image`) — ADR residual risk 10.
     */
    fun isStaticId(id: String): Boolean {
        synchronized(cache) { cache[id] }?.let { return it }
        val verdict = judge(id)
        synchronized(cache) { cache[id] = verdict }
        return verdict
    }

    /**
     * Review AF4: the verdict is frame-FREE and never changes for a raw id, so it is memoized process-wide
     * in a small bounded LRU ([CACHE_SIZE] entries, access order, evicting the eldest) under its own lock.
     */
    private const val CACHE_SIZE = 512

    private val cache = object : LinkedHashMap<String, Boolean>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > CACHE_SIZE
    }

    private fun judge(id: String): Boolean {
        if (!ResourceIdGrammar.isStaticShape(id)) return false
        // Review PP1: camelCase segments are words too (`deliverToSam` → "deliver To Sam"), by the same
        // rule the frame-level check uses. Review PP4: only the CASE-SENSITIVE-initial name shape runs on
        // the id path — the IGNORE_CASE anchored variant nulled every `option_a` / `tab_b` chrome id.
        // Review SS3 (id path only): the name shape is FULLY case-sensitive (an uppercase-led first token —
        // Capitalized or, since ZZ4, all-caps — and an uppercase initial; `tab B` / `option A` are chrome),
        // and a marker / lead-in withholds only when the token AFTER it is Capitalized (a name):
        // `deliver_to_Sam` is absent, `deliver_to_label` travels. SS8: an id with no canonical form is not static.
        val spoken = CensusHash.canonical(
            ResourceIdGrammar.namePart(id).split(ID_SEPARATORS)
                .joinToString(" ") { LetterRuns.camelSegments(it).joinToString(" ") },
        ) ?: return false
        if (PiiShapes.containsMask(spoken) || PiiShapes.FIRST_LAST_INITIAL_ID_PATH_REGEX.containsMatchIn(spoken)) return false
        val tokens = spoken.split(' ')
        return tokens.indices.none { i ->
            val tail = tokens.subList(i, tokens.size).joinToString(" ")
            val prefix = CustomerTextMarkers.unredactedMarker(tail) ?: PiiShapes.customerLeadIn(tail)
            prefix != null && tail.length > prefix.length && isCapitalAt(tail, prefix.length)
        }
    }

    private val ID_SEPARATORS = Regex("[_.:-]")

    /** UU5/WW6: the CODE POINT at [index] is an uppercase letter (a supplementary-plane capital counts). */
    internal fun isCapitalAt(value: String, index: Int): Boolean = Character.isUpperCase(value.codePointAt(index))
}
