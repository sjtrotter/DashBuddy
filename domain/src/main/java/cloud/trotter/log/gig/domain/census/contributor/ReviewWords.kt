package cloud.trotter.log.gig.domain.census.contributor

import cloud.trotter.log.gig.domain.capture.CensusRecord

/**
 * #1626: the words a screen review may show, by census slot id (`root/0/text`, `windowTitle`). Only the slots the
 * skeleton hashed (the census filter let them through as possible app wording) whose word also passed the review
 * screen's own covers (customer and backstop markers, the dasher's greeting, account and place labels). Every other
 * slot has no entry, so no other screen text is ever held for a review.
 *
 * Held in memory only, never written to disk, logged or sent as such: [toString] prints no word, and the class is not
 * serializable. A process death discards it. A word leaves the phone only through `POST /v1/contributor/words`, after
 * the server reports its word gate open for that exact slot and hash.
 */
class ReviewWords(words: Map<String, String>) {
    private val bySlot: Map<String, String> = words.toMap()

    val slots: Set<String> get() = bySlot.keys

    operator fun get(slot: String): String? = bySlot[slot]

    fun isEmpty(): Boolean = bySlot.isEmpty()

    override fun toString(): String = "ReviewWords(${bySlot.size} slots, [redacted])"

    override fun equals(other: Any?): Boolean = other is ReviewWords && other.bySlot == bySlot

    override fun hashCode(): Int = bySlot.hashCode()
}

/**
 * #1626: takes review candidates from the census sink. [wantsCandidates] is a non-blocking snapshot the pipeline reads
 * before it collects words for a frame; [offer] is called after the record went to the upload spool, and checks the
 * contributor gate itself before it keeps anything.
 */
interface ContributorReviewIntake {
    val wantsCandidates: Boolean
    suspend fun offer(record: CensusRecord)

    companion object {
        val NONE: ContributorReviewIntake = object : ContributorReviewIntake {
            override val wantsCandidates: Boolean get() = false
            override suspend fun offer(record: CensusRecord) = Unit
        }
    }
}

/** #1626: the upload worker reports the skeleton items the server stored (accepted or duplicate), byte for byte. */
fun interface UploadedSkeletons {
    suspend fun uploaded(itemsJson: List<String>)

    companion object {
        val NONE: UploadedSkeletons = UploadedSkeletons { }
    }
}
