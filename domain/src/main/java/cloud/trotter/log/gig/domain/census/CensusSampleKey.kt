package cloud.trotter.log.gig.domain.census

import cloud.trotter.census.contract.CensusSkeletonDto
import cloud.trotter.census.contract.KindClassifier
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonDto
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.census.contract.sha256OrNull

/**
 * #1620 (ADR-0011 §8, §21): the key of the one-sample-per-variant-per-UTC-day rule. A variant is a layout and its
 * value formats: the schema, the platform and its app version, then for a screen the structural fingerprint (classes,
 * ids, nesting), each node's flags and which text fields it has, and for a notification its channel, which fields it
 * has and its action count; every slot adds only its format template.
 *
 * It never reads what the words were. A slot's word hash, its word count and whether a filter withheld it all
 * depend on the text on screen, often a customer's or a store's, so they would sample per customer; every slot that
 * is not a format template reads the same `text`. Two frames that differ only in a name are one variant.
 */
object CensusSampleKey {

    /** The key of [item]: 64 hex, or null when no digest is available (the caller then keeps nothing). */
    fun of(item: CensusSkeletonDto): String? = sha256OrNull(canonical(item))

    internal fun canonical(item: CensusSkeletonDto): String = buildString {
        line("schema", item.schemaId)
        line("platform", item.platform)
        line("platformAppVersion", item.platformAppVersion ?: "")
        when (item) {
            is UiSkeletonDto -> {
                // A screen's fingerprint is class, id and nesting only, never text (ADR-0011 §8).
                line("fingerprint", item.fingerprint)
                line("title", item.windowTitle?.let(::format) ?: "")
                node(item.root)
            }
            // A notification's fingerprint covers its slots' hashes and kinds, so it would key on words: the
            // structure here is the channel, which fields are present, and each slot's format.
            is NotificationSkeletonDto -> {
                line("kind", item.kind.name)
                line("channel", item.channelId)
                NotifTextField.entries.forEach { line(it.name, item.slots[it]?.let(::format) ?: "") }
                line("actions", item.actions.joinToString(",") { format(it) })
            }
        }
    }

    private fun StringBuilder.node(node: UiSkeletonNodeDto) {
        val flags = listOf(node.isClickable, node.isEnabled, node.hasClickAction, node.isVisibleToUser)
            .joinToString("") { if (it) "1" else "0" } + node.isChecked
        val fields = node.text.toSortedMap().entries.joinToString(",") { (key, slot) -> "$key=${format(slot)}" }
        line("node", "$flags;$fields;${node.children.size}")
        node.children.forEach { node(it) }
    }

    /** A format template is a layout fact; anything else (a hash, a word count, digits, mixed, withheld) is `text`. */
    private fun format(slot: TextSlot): String =
        if (slot.kind == KindClassifier.SHAPE) "shape:${slot.shape}" else "text"

    /** Every value here is a closed-grammar wire token (no newline), so one line per field is unambiguous. */
    private fun StringBuilder.line(name: String, value: String) {
        append(name).append('=').append(value).append('\n')
    }
}
