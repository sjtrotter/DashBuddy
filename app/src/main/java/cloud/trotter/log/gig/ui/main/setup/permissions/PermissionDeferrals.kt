package cloud.trotter.log.gig.ui.main.setup.permissions

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * #1457 — the permission steps the dasher put off for now ("Not now", Don't allow on the screen
 * reading step, or closing the sheet). In memory only, in a process-wide singleton: a deferral holds
 * across rotation, navigation and a trip to Android settings, and only a cold start (a new process)
 * clears it, or Home's gate card, which asks again on purpose. It records nothing: no consent, no
 * receipt, no denial. Screen events' own Don't allow is a lasting decision written through
 * `EventReceiptPreferences`, never a deferral here.
 */
@Singleton
class PermissionDeferrals @Inject constructor() {
    private val _deferred = MutableStateFlow<Set<PermissionStep>>(emptySet())
    val deferred: StateFlow<Set<PermissionStep>> = _deferred.asStateFlow()

    fun defer(steps: Collection<PermissionStep>) = _deferred.update { it + steps }

    fun clear() {
        _deferred.value = emptySet()
    }
}
