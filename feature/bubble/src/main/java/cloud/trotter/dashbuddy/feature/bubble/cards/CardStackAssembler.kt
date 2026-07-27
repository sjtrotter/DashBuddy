package cloud.trotter.dashbuddy.feature.bubble.cards

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.session.CardSource

/**
 * The bubble HUD's card stack plus the per-card platform chips it renders (#867).
 *
 * [platformLabels] is keyed by [FlowCardSnapshot.id] and is EMPTY whenever fewer than two sessions
 * are live — a single-session HUD renders no chips at all. It is a sidecar map rather than a field
 * on [FlowCardSnapshot] on purpose: the snapshot is the frozen *content* of a phase (folded from
 * the event log and shared with the analytics drill-down), while "which platform's surface am I
 * looking at right now" is a property of the current presentation, not of the historical fact.
 */
data class BubbleCards(
    val stack: CardStack = CardStack.Empty,
    val platformLabels: Map<String, String> = emptyMap(),
) {
    companion object {
        val Empty = BubbleCards()
    }
}

/**
 * Assembles the rendered stack from one folded card list per displayed session.
 *
 * Single-source (every non-merged mode) is a pass-through: the fold's own order is preserved
 * byte-for-byte, so the shipped presentation is unchanged. Multi-source ([BubbleSessionMode.MERGED]
 * (`cloud.trotter.dashbuddy.domain.model.bubble.BubbleSessionMode.MERGED`)) is a **k-way merge on
 * `phaseStartedAt` that preserves each session's internal order** — deliberately not a global
 * `sortedBy`, which would re-order cards *within* a session (the fold emits cards in close order,
 * not start order, so sorting a single session's list is not a no-op).
 */
object CardStackAssembler {

    fun assemble(
        folded: List<Pair<CardSource, List<FlowCardSnapshot>>>,
        activeCard: FlowCardSnapshot?,
        activePlatform: Platform?,
        labelsVisible: Boolean,
    ): BubbleCards {
        val completed = interleaveByStart(folded.map { it.second })
        val stack = CardStack.of(completed = completed, active = activeCard)

        if (!labelsVisible) return BubbleCards(stack = stack)

        val labels = buildMap {
            folded.forEach { (source, cards) ->
                val label = source.platform?.shortName?.takeIf { it.isNotBlank() } ?: return@forEach
                cards.forEach { put(it.id, label) }
            }
            activeCard?.let { live ->
                activePlatform?.shortName?.takeIf { it.isNotBlank() }?.let { put(live.id, it) }
            }
        }
        return BubbleCards(stack = stack, platformLabels = labels)
    }

    /**
     * Merge already-ordered per-session lists into one chronological list, always taking the head
     * with the earliest `phaseStartedAt` and breaking ties by list order (stable). Each input
     * list's relative order is preserved exactly.
     */
    private fun interleaveByStart(lists: List<List<FlowCardSnapshot>>): List<FlowCardSnapshot> {
        val nonEmpty = lists.filter { it.isNotEmpty() }
        if (nonEmpty.size <= 1) return nonEmpty.firstOrNull() ?: emptyList()

        val cursors = IntArray(nonEmpty.size)
        val total = nonEmpty.sumOf { it.size }
        val merged = ArrayList<FlowCardSnapshot>(total)
        repeat(total) {
            var pick = -1
            for (i in nonEmpty.indices) {
                val idx = cursors[i]
                if (idx >= nonEmpty[i].size) continue
                if (pick < 0 ||
                    nonEmpty[i][idx].phaseStartedAt < nonEmpty[pick][cursors[pick]].phaseStartedAt
                ) {
                    pick = i
                }
            }
            if (pick < 0) return@repeat
            merged.add(nonEmpty[pick][cursors[pick]])
            cursors[pick]++
        }
        return merged
    }
}
