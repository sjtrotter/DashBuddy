package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.wipe.PhoneWipe
import cloud.trotter.log.gig.core.data.wipe.WipeFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the "Delete everything on this phone" dialog shows (#1579). */
data class WipeDialog(
    /** Null while closed. */
    val state: WipeFlow.State?,
    /** The confirmation names the census request and what the server keeps (live, [PhoneWipe.censusDisclosed]). */
    val censusDisclosed: Boolean,
) {
    companion object {
        val CLOSED = WipeDialog(state = null, censusDisclosed = false)
    }
}

/** #1579: Settings > Data & privacy > Delete everything on this phone. [PhoneWipe] runs the flow and owns its state. */
@HiltViewModel
class WipePhoneViewModel @Inject constructor(private val wipe: PhoneWipe) : ViewModel() {

    val dialog: StateFlow<WipeDialog> = combine(wipe.progress, wipe.censusDisclosed, ::WipeDialog)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WipeDialog.CLOSED)

    fun open() = wipe.open()

    fun confirm() = wipe.confirm()

    /** Cancel, or OK on a failure. Nothing is deleted. */
    fun cancel() = wipe.cancel()

    fun deleteNow() = wipe.deleteNow()

    /** Wait: nothing is deleted and the census request keeps retrying (#1622). */
    fun waitForServer() = wipe.waitForServer()
}
