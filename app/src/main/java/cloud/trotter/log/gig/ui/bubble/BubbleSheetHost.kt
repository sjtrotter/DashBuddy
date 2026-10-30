package cloud.trotter.log.gig.ui.bubble

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.log.gig.feature.bubble.hud.undo.newestBand
import kotlinx.coroutines.flow.Flow

internal enum class EntrySheet { ODOMETER, CASH_TIP, EXPENSE, GOAL }

internal data class SheetBand(val token: Long, val content: @Composable () -> Unit)

@Stable
internal interface SheetPort {
    val key: EntrySheet
    val open: Boolean
    val entryId: Long
    val compact: Boolean get() = false
    val completed: Flow<Long>
    fun completionHandled()
    fun show()
    fun dismiss()
    fun back()
    @Composable fun Band(): SheetBand? = null
}

internal data class SheetSlot(val port: SheetPort, val content: @Composable ColumnScope.() -> Unit)

@Stable
internal interface BubbleSheetHost {
    val open: Boolean
    val compact: Boolean
    fun show(key: EntrySheet?)
    fun back()
    fun outside()
    @Composable fun Sheet(scope: ColumnScope)
    @Composable fun Band()
    @Composable fun BandVisible(): Boolean
}

@Composable
internal fun rememberBubbleSheetHost(slots: List<SheetSlot>): BubbleSheetHost {
    require(slots.map { it.port.key }.distinct().size == slots.size) { "Sheet keys must be unique" }
    val currentSlots = rememberUpdatedState(slots)
    val lastSheet = rememberSaveable { mutableStateOf(slots.firstOrNull()?.port?.key) }
    val host = remember { RegisteredSheetHost(currentSlots, lastSheet) }
    // These collectors outlive selected content, including its exit animation.
    for (slot in slots) key(slot.port.key) {
        val port = slot.port
        val completed = port.completed
        LaunchedEffect(port, completed) {
            completed.collect { id ->
                if (lastSheet.value == port.key && port.open && port.entryId == id) port.dismiss()
                port.completionHandled()
            }
        }
    }
    return host
}

private class RegisteredSheetHost(
    private val slots: State<List<SheetSlot>>,
    private val lastSheet: MutableState<EntrySheet?>,
) : BubbleSheetHost {
    private val current get() = slots.value.find { it.port.key == lastSheet.value }
    override val open get() = current?.port?.open == true
    override val compact get() = current?.port?.compact == true

    override fun show(key: EntrySheet?) {
        val next = key?.let { requested -> slots.value.first { it.port.key == requested } }
        slots.value.forEach { if (it.port.key != key) it.port.dismiss() }
        // A close retains content for the entire slide out.
        if (next != null) {
            lastSheet.value = key
            next.port.show()
        }
    }

    override fun back() { current?.port?.takeIf { it.open }?.back() }
    override fun outside() { current?.port?.takeIf { it.open && it.compact }?.dismiss() }

    @Composable override fun Sheet(scope: ColumnScope) {
        current?.let { slot -> key(slot.port.key) { slot.content(scope) } }
    }

    @Composable private fun newest(): SheetBand? {
        // Evaluate every candidate, even after finding a visible band: each owns its expiry state.
        val bands = slots.value.map { slot -> key(slot.port.key) { slot.port.Band() } }
        val winner = newestBand(bands.mapIndexed { index, band -> index to band?.token }.toMap())
        return winner?.let { bands[it] }
    }

    @Composable override fun Band() { newest()?.content?.invoke() }
    @Composable override fun BandVisible(): Boolean = newest() != null
}
