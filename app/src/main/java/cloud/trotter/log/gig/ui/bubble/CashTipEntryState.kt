package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.*
import cloud.trotter.log.gig.feature.bubble.hud.keypad.MoneyEntry

@Stable
internal class CashTipEntryState(
    private val openState: MutableState<Boolean>,
    private val pageState: MutableState<CashTipPage>,
    private val selectedState: MutableState<Long?>,
    private val totalState: MutableState<Long>,
    private val signState: MutableState<CashTipSign>,
    private val otherState: MutableState<String>,
    private val dashState: MutableState<String?> = mutableStateOf(null),
    private val entryIdState: MutableState<Long> = mutableStateOf(0L),
) {
    var entryId by entryIdState
        private set
    var dashId by dashState
        private set
    var open by openState
    var page by pageState
    var selectedDeliveryId by selectedState
    var totalCents by totalState
        private set
    var sign by signState
    var otherAmount by otherState
        private set

    fun show(latestId: Long?, dashId: String? = null) {
        if (open) return
        entryId++
        this.dashId = dashId
        page = CashTipPage.COMPACT
        selectedDeliveryId = latestId
        totalCents = 0
        sign = CashTipSign.PLUS
        otherAmount = ""
        open = true
    }
    fun select(id: Long) { selectedDeliveryId = id; page = CashTipPage.COMPACT }
    fun add(cents: Long) {
        require(cents >= 0)
        totalCents = if (sign == CashTipSign.PLUS) totalCents + cents.coerceAtMost(MAX_CENTS - totalCents)
            else (totalCents - cents).coerceAtLeast(0)
    }
    fun other() { otherAmount = ""; page = CashTipPage.OTHER_AMOUNT }
    fun digit(n: Int) { otherAmount = MoneyEntry(otherAmount).digit(n).digits }
    fun point() { otherAmount = MoneyEntry(otherAmount).point().digits }
    fun backspace() { otherAmount = MoneyEntry(otherAmount).backspace().digits }
    fun doneOther() {
        if (otherAmount.isNotEmpty()) totalCents = MoneyEntry(otherAmount).cents.coerceIn(0, MAX_CENTS)
        page = CashTipPage.COMPACT
    }
    fun back() {
        if (page == CashTipPage.COMPACT) open = false else page = CashTipPage.COMPACT
    }
    companion object { const val MAX_CENTS = 99_999_999L }
}

@Composable
internal fun rememberCashTipEntryState(): CashTipEntryState {
    val open = rememberSaveable { mutableStateOf(false) }
    val page = rememberSaveable { mutableStateOf(CashTipPage.COMPACT) }
    val selected = rememberSaveable { mutableStateOf<Long?>(null) }
    val total = rememberSaveable { mutableStateOf(0L) }
    val sign = rememberSaveable { mutableStateOf(CashTipSign.PLUS) }
    val other = rememberSaveable { mutableStateOf("") }
    val dash = rememberSaveable { mutableStateOf<String?>(null) }
    val entryId = rememberSaveable { mutableStateOf(0L) }
    return remember { CashTipEntryState(open, page, selected, total, sign, other, dash, entryId) }
}
