package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.ResourceIdGrammar
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
    fun isStaticId(id: String): Boolean = verdict(id) == IdVerdict.STATIC

    /** What the frame-free id judgement makes of a raw view id (review AJ6: ONE call per node). */
    enum class IdVerdict {
        /** Fails the static resource-name grammar (a per-frame UUID tag): null on the wire. */
        DYNAMIC,

        /** Static shape, no PII predicate fires: travels (subject to the frame rule). */
        STATIC,

        /** Static shape whose name part reads as customer PII: the sentinel `~` on the wire (AH1). */
        PII_WITHHELD,
    }

    /** [id]'s [IdVerdict], memoized process-wide (review AF4 — frame-free, never changes for a raw id). */
    fun verdict(id: String): IdVerdict {
        synchronized(cache) { cache[id] }?.let { return it }
        val verdict = when {
            !ResourceIdGrammar.isStaticShape(id) -> IdVerdict.DYNAMIC
            namePartCarriesPii(ResourceIdGrammar.namePart(id)) -> IdVerdict.PII_WITHHELD
            else -> IdVerdict.STATIC
        }
        synchronized(cache) { cache[id] = verdict }
        return verdict
    }

    /**
     * Review AF4: the verdict is frame-FREE and never changes for a raw id, so it is memoized process-wide
     * in a small bounded LRU ([CACHE_SIZE] entries, access order, evicting the eldest) under its own lock.
     */
    private const val CACHE_SIZE = 512

    private val cache = object : LinkedHashMap<String, IdVerdict>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IdVerdict>?): Boolean = size > CACHE_SIZE
    }

    /**
     * Does an id-SHAPED value ([namePart] — an id's name part, or a `uid` test tag, review AJ1) read as
     * customer PII once its separators (`_`, `.`, `-`, `:`) are spaces and its camelCase segments are
     * words? The id-path name shape or a marker / lead-in before a Capitalized token. No canonical form →
     * true (fail closed).
     */
    fun namePartCarriesPii(namePart: String): Boolean {
        // Review PP1: camelCase segments are words too (`deliverToSam` → "deliver To Sam"), by the same
        // rule the frame-level check uses. Review PP4: only the CASE-SENSITIVE-initial name shape runs on
        // the id path — the IGNORE_CASE anchored variant nulled every `option_a` / `tab_b` chrome id.
        // Review SS3 (id path only): the name shape is FULLY case-sensitive (an uppercase-led first token —
        // Capitalized or, since ZZ4, all-caps — and an uppercase initial; `tab B` / `option A` are chrome),
        // and a marker / lead-in withholds only when the token AFTER it is Capitalized (a name):
        // `deliver_to_Sam` is withheld, `deliver_to_label` travels. SS8: no canonical form reads as PII.
        // AJ8: no mask check — the id grammar rejects `[`, so a mask literal can never reach the id path
        // (a `uid` mask is caught by the text steps it continues through).
        val spoken = CensusHash.canonical(
            namePart.split(ID_SEPARATORS).joinToString(" ") { LetterRuns.camelSegments(it).joinToString(" ") },
        ) ?: return true
        if (PiiShapes.FIRST_LAST_INITIAL_ID_PATH_REGEX.containsMatchIn(spoken)) return true
        val tokens = spoken.split(' ')
        return tokens.indices.any { i ->
            val tail = tokens.subList(i, tokens.size).joinToString(" ")
            val prefix = CustomerTextMarkers.unredactedMarker(tail) ?: PiiShapes.customerLeadIn(tail)
            prefix != null && tail.length > prefix.length && isCapitalAt(tail, prefix.length)
        }
    }

    private val ID_SEPARATORS = Regex("[_.:-]")

    /** UU5/WW6: the CODE POINT at [index] is an uppercase letter (a supplementary-plane capital counts). */
    internal fun isCapitalAt(value: String, index: Int): Boolean = Character.isUpperCase(value.codePointAt(index))
}
