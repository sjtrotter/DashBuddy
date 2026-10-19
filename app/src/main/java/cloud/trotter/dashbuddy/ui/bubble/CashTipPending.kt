package cloud.trotter.dashbuddy.ui.bubble

internal object CashTipPending {
    fun footAmountCents(commit: CashTipCommit?): Long? = commit?.strip?.amountCents
}
