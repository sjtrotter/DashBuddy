package cloud.trotter.log.gig.ui.bubble

import cloud.trotter.log.gig.feature.bubble.hud.undo.UndoStripModel

/** Reversal metadata stays with the app's write owner; the shared strip only needs display data. */
internal data class CashTipCommit(
    val strip: UndoStripModel,
    val deliveryId: Long,
    val sessionId: String?,
    val taskId: String,
    val previousCashTipCents: Long,
    val expectedCashTipCents: Long,
    val entryId: Long,
)
