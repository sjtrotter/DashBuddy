package cloud.trotter.log.gig.ui.bubble

internal object CashTipPending {
    fun footAmountCents(commit: CashTipCommit?): Long? = commit?.strip?.amountCents
}
