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

/** One newest-wins decision across all three sources. Invisible strips cannot win. */
enum class UndoBandContent { ODOMETER, CASH_TIP, EXPENSE }
fun undoBandContent(odometerToken: Long?, cashTipToken: Long?, expenseToken: Long?): UndoBandContent? =
    listOfNotNull(odometerToken?.let { UndoBandContent.ODOMETER to it },
        cashTipToken?.let { UndoBandContent.CASH_TIP to it },
        expenseToken?.let { UndoBandContent.EXPENSE to it }).maxByOrNull { it.second }?.first
