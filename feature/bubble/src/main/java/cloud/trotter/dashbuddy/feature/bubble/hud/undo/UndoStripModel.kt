package cloud.trotter.dashbuddy.feature.bubble.hud.undo

import cloud.trotter.dashbuddy.feature.bubble.R

/** A committed adjustment, never an unpersisted entry. Owned by the HUD ViewModel. */
data class UndoStripModel(
    val amountCents: Long,
    val where: String?,
    val committedSequenceId: Long,
    val deadline: Long,
    val token: Long,
    /** Feature string resource for what was logged; later sheets supply their own label. */
    val whatLabel: Int = R.string.hud_cash_tip_title,
) {
    companion object { const val WINDOW_MS = 10_000L }
}

/** One rule shared by the host and its tests. */
fun cashTipUndoWins(strip: UndoStripModel?, odometerToken: Long?, now: Long): Boolean =
    strip != null && now < strip.deadline && (odometerToken == null || strip.token > odometerToken)
