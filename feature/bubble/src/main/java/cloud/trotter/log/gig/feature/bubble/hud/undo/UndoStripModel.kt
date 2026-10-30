package cloud.trotter.log.gig.feature.bubble.hud.undo

import cloud.trotter.log.gig.feature.bubble.R

/** A committed adjustment, never an unpersisted entry. Owned by the HUD ViewModel. */
data class UndoStripModel(
    val amountCents: Long,
    val where: String?,
    val committedSequenceId: Long,
    val deadline: Long,
    val token: Long,
    /** Feature string resource for what was logged; later sheets supply their own label. */
    val whatLabel: Int = R.string.hud_cash_tip_title,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val whereFallback: Int? = R.string.hud_cash_tip_delivery,
) {
    fun visible(now: Long): Boolean = busy || failed || now < deadline

    companion object { const val WINDOW_MS = 10_000L }
}

/** One newest-wins decision over all candidates. Invisible bands have no token. */
fun <K : Any> newestBand(tokens: Map<K, Long?>): K? =
    tokens.entries.filter { it.value != null }.maxByOrNull { it.value!! }?.key
