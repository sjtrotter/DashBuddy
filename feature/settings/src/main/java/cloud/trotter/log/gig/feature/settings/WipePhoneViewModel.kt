package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.wipe.PhoneWipe
import cloud.trotter.log.gig.core.data.wipe.WipeProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the "Delete everything on this phone" dialog shows (#1579). */
sealed interface WipeDialog {
    data object Closed : WipeDialog

    /** The one confirmation; [census] adds the line that the census server is asked first. */
    data class Confirm(val census: Boolean) : WipeDialog

    /** The wipe under way, as [PhoneWipe] reports it. */
    data class Running(val progress: WipeProgress) : WipeDialog
}

/**
 * #1579: Settings > Data & privacy > Delete everything on this phone. The confirmation is this screen's; once
 * confirmed, [PhoneWipe] runs the census request and the wipe on the application scope and owns the progress.
 */
@HiltViewModel
class WipePhoneViewModel @Inject constructor(private val wipe: PhoneWipe) : ViewModel() {

    /** Null while closed; otherwise whether the confirmation names the census request. */
    private val confirming = MutableStateFlow<Boolean?>(null)

    val dialog: StateFlow<WipeDialog> = combine(confirming, wipe.progress) { census, progress ->
        when {
            progress != WipeProgress.Idle -> WipeDialog.Running(progress)
            census != null -> WipeDialog.Confirm(census)
            else -> WipeDialog.Closed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WipeDialog.Closed)

    /** The settings row: reads whether the phone holds an enrolled census ID, then shows the confirmation. */
    fun open() {
        if (wipe.progress.value != WipeProgress.Idle) return
        viewModelScope.launch { confirming.value = wipe.censusEnrolled() }
    }

    /** The confirmation's Delete everything. The confirmation stays until [PhoneWipe.progress] moves on. */
    fun confirm() {
        if (confirming.value == null) return
        wipe.start()
    }

    /** "Delete now", after the census server did not confirm. */
    fun wipeNow() = wipe.wipeNow()

    /** Cancel, Wait or OK. Nothing is deleted. */
    fun dismiss() {
        confirming.value = null
        wipe.dismiss()
    }
}
