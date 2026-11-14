package cloud.trotter.log.gig.core.data.census.contributor

import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.ContributorVariant
import cloud.trotter.census.contract.contributor.QuarantineNote
import cloud.trotter.log.gig.domain.census.contributor.ReviewWords
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive

/** #1626: one confirmation: a slot and the slot's public census hash (16 hex), never the word. */
data class Confirmation(val slot: String, val hash: String)

/** #1626: a word the server's gate opened for, with the slot and hash it answers for. */
class OpenWord(val slot: String, val hash: String, val word: String) {
    override fun toString(): String = "OpenWord($slot, [redacted])"
}

/**
 * #1626: the one owner of what each screen-review request body holds (census#81, `docs/API.md` "Contributor tier").
 * Bodies are compact JSON, keys in the documented order, so the transport signs and sends exactly these bytes.
 *
 * - classes: the variant and the slot ids of the reviewed skeleton, nothing else;
 * - review: the variant, confirmations (slot and public hash) and role tags (slot and a role from the closed list);
 *   no word and no value;
 * - gates: the variant and the confirmed (slot, hash) pairs;
 * - words: the variant and words whose gate the server reported open, each checked here to hash to its slot's hash.
 * - artifact (#1627): the "Ask the developer" artifact in the server's canonical form; quarantine: that artifact,
 *   base64url, and the optional note from the closed list.
 */
object ContributorRequests {
    /** The server's per-request caps. */
    const val MAX_CLASS_SLOTS: Int = 1024
    const val MAX_REVIEW_ITEMS: Int = 256
    const val MAX_GATE_ITEMS: Int = 64
    const val MAX_WORD_ITEMS: Int = 16
    /** The server's cap on a decoded quarantine artifact (128 KiB). */
    const val MAX_ARTIFACT_BYTES: Int = 131_072

    fun classes(variant: ContributorVariant, slots: List<String>): ByteArray {
        require(slots.size in 1..MAX_CLASS_SLOTS)
        return body("{\"variant\":${variant.json()},\"slots\":[${slots.joinToString(",") { string(it) }}]}")
    }

    fun review(variant: ContributorVariant, confirmations: List<Confirmation>, roles: List<Pair<String, ContributorRole>>): ByteArray {
        require(confirmations.size + roles.size in 1..MAX_REVIEW_ITEMS)
        confirmations.forEach { require(CensusHash.isWellFormed(it.hash)) }
        val confirmed = confirmations.joinToString(",") { "{\"slot\":${string(it.slot)},\"hash\":${string(it.hash)}}" }
        val tagged = roles.joinToString(",") { (slot, role) -> "{\"slot\":${string(slot)},\"role\":${string(role.wire)}}" }
        return body("{\"variant\":${variant.json()},\"confirmations\":[$confirmed],\"roles\":[$tagged]}")
    }

    fun gates(variant: ContributorVariant, confirmations: List<Confirmation>): ByteArray {
        require(confirmations.size in 1..MAX_GATE_ITEMS)
        confirmations.forEach { require(CensusHash.isWellFormed(it.hash)) }
        val slots = confirmations.joinToString(",") { "{\"slot\":${string(it.slot)},\"hash\":${string(it.hash)}}" }
        return body("{\"variant\":${variant.json()},\"slots\":[$slots]}")
    }

    /**
     * Only words the gate opened for reach this; a word whose census hash is not its slot's hash is refused here, so
     * a word can never travel under another slot's opening.
     */
    fun words(variant: ContributorVariant, words: List<OpenWord>): ByteArray {
        require(words.size in 1..MAX_WORD_ITEMS)
        words.forEach { require(CensusHash.of(it.word) == it.hash) { "a word must hash to its slot's hash" } }
        val items = words.joinToString(",") {
            "{\"slot\":${string(it.slot)},\"hash\":${string(it.hash)},\"word\":${string(it.word)}}"
        }
        return body("{\"variant\":${variant.json()},\"words\":[$items]}")
    }

    /**
     * #1627: the "Ask the developer" artifact, in census#81's canonical form (`docs/API.md` "Quarantine"):
     * `{"skeleton":<the contract's serialization>,"confirmations":[{"slot","hash"}],"roles":[{"slot","role"}],
     * "words":[{"slot","hash","word"}]}`, keys in that order, every array present, compact. The server re-serializes
     * what it validates the same way and returns the SHA-256 of those bytes, so a matching hash means the sealed copy
     * is these bytes.
     *
     * [skeleton] is the outbound skeleton the review screen described (hashes, kinds, formats, `withheld`; never screen
     * text). `words` is built here and only here, from [open]: the (slot, hash) pairs the server's gate reported open
     * for this request. A confirmation outside [open] stays a hash; a word held for it is never read. A word whose
     * census hash is not its slot's hash is left out, so it can never travel under another slot's opening.
     */
    fun artifact(
        skeleton: CensusSkeletonDto,
        confirmations: List<Confirmation>,
        roles: List<Pair<String, ContributorRole>>,
        words: ReviewWords?,
        open: Set<Confirmation>,
    ): ByteArray {
        require(confirmations.size + roles.size <= MAX_REVIEW_ITEMS)
        confirmations.forEach { require(CensusHash.isWellFormed(it.hash)) }
        val cleared = openWords(confirmations, words, open)
        val confirmed = confirmations.joinToString(",") { "{\"slot\":${string(it.slot)},\"hash\":${string(it.hash)}}" }
        val tagged = roles.joinToString(",") { (slot, role) -> "{\"slot\":${string(slot)},\"role\":${string(role.wire)}}" }
        val sent = cleared.joinToString(",") {
            "{\"slot\":${string(it.slot)},\"hash\":${string(it.hash)},\"word\":${string(it.word)}}"
        }
        return body(
            "{\"skeleton\":${CensusSkeletonSchema.serialize(skeleton)},\"confirmations\":[$confirmed]," +
                "\"roles\":[$tagged],\"words\":[$sent]}",
        )
    }

    /** The words [artifact] carries: a confirmation the gate reported open, whose held word hashes to its hash. */
    internal fun openWords(confirmations: List<Confirmation>, words: ReviewWords?, open: Set<Confirmation>): List<OpenWord> {
        if (words == null) return emptyList()
        return confirmations
            .filter { it in open }
            .mapNotNull { c -> words[c.slot]?.takeIf { CensusHash.of(it) == c.hash }?.let { OpenWord(c.slot, c.hash, it) } }
            .take(MAX_WORD_ITEMS)
    }

    /** #1627: the quarantine request: [artifact] as unpadded base64url and, when chosen, the note from the closed list. */
    fun quarantine(artifact: ByteArray, note: QuarantineNote?): ByteArray {
        require(artifact.isNotEmpty() && artifact.size <= MAX_ARTIFACT_BYTES)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(artifact)
        val noted = note?.let { ",\"note\":${string(it.wire)}" }.orEmpty()
        return body("{\"artifact\":${string(encoded)}$noted}")
    }

    private fun string(value: String): String = JsonPrimitive(value).toString()

    private fun body(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)
}
