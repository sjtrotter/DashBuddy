package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel

/** Reversal metadata stays with the app's write owner; the shared strip only needs display data. */
internal data class CashTipCommit(
    val strip: UndoStripModel,
    val deliveryId: Long,
    val sessionId: String?,
    val taskId: String,
    val previousCashTip: Double,
    val expectedCashTip: Double,
    val entryId: Long,
)
