package cloud.trotter.dashbuddy.ui.main.setup.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import cloud.trotter.dashbuddy.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Owns the permission-gate state for [PermissionsBottomSheet] (#944): which of the five required
 * OS permissions are still missing — and, since #1151's re-sequencing (dev, 2026-09-30), the
 * Screen-events consent step that comes FIRST and gates the accessibility grant.
 *
 * UDF (principle 1): the five reads used to live in `remember { … }` initializers inside the
 * composable, with the resume re-poll and both launcher callbacks writing back into five
 * composition-local `mutableStateOf`s — composition doing the sensing. The reads are hoisted here;
 * the UI observes an immutable [PermissionsUiState] and dispatches exactly one event up
 * ([refresh]).
 *
 * The permission set is OS-owned and can change while the app is backgrounded (the user toggling
 * accessibility in system Settings), so there is nothing to observe reactively — it is *polled* on
 * every edge that can have changed it: construction, the sheet entering composition, every
 * ON_RESUME, and each permission-launcher result. Polling all five on every edge (rather than only
 * the one just requested) keeps a single read path: the OS is the source of truth, never a
 * launcher's echoed boolean.
 *
 * This holds no presentation state — which card is on screen, and the exit animation that outlives
 * the last grant, belong to the sheet (see [PermissionsBottomSheet]). Keeping the ViewModel to
 * pure OS facts is also what makes it safe for it to outlive a sheet on the nav back-stack entry.
 */
@HiltViewModel
class PermissionsViewModel internal constructor(
    private val pollOs: () -> OsPermissionPoll,
    private val eventReceipt: EventReceiptPreferences,
    private val isDebugBuild: Boolean,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        eventReceipt: EventReceiptPreferences,
    ) : this({ OsPermissionPoll.of(context) }, eventReceipt, BuildConfig.DEBUG)

    private val osPoll = MutableStateFlow(pollOs())

    /**
     * #1151 (dev re-sequencing 2026-09-30): the OS poll joined with the event-receipt consent, so
     * the Screen-events step leads the queue and gates the accessibility step. Seeded from the
     * current values — never a fabricated "nothing missing" before the first read.
     */
    val uiState: StateFlow<PermissionsUiState> =
        combine(osPoll, eventReceipt.consent) { poll, consent ->
            buildPermissionsUiState(poll, consent, isDebugBuild)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = buildPermissionsUiState(osPoll.value, eventReceipt.consent.value, isDebugBuild),
        )

    /**
     * Re-poll the OS. Dispatched by the UI when the sheet enters composition, on ON_RESUME, and
     * after a permission-launcher result. Cheap synchronous system-service reads — the same ones
     * that used to run inline in composition.
     */
    fun refresh() {
        osPoll.value = pollOs()
    }

    /**
     * #1151 — the Screen-events step's Allow / Don't allow. Writes through the consent's one owner
     * ([EventReceiptPreferences]); either answer moves the queue on to the accessibility step (a
     * debug build that declined is replaced by `DebugEventReceiptShell` instead).
     */
    fun onScreenEventsDecision(allow: Boolean) {
        viewModelScope.launch { eventReceipt.set(EventReceiptConsent.of(allow)) }
    }
}

/** One OS poll of the five required permissions. */
data class OsPermissionPoll(
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
    val location: Boolean = false,
    val postNotifications: Boolean = false,
    val bubbles: Boolean = false,
) {
    companion object {
        fun of(context: Context) = OsPermissionPoll(
            accessibility = PermissionUtils.isAccessibilityServiceEnabled(context),
            notificationListener = PermissionUtils.isNotificationListenerEnabled(context),
            location = PermissionUtils.hasLocationPermission(context),
            postNotifications = PermissionUtils.hasPostNotificationsPermission(context),
            bubbles = PermissionUtils.hasFullBubblePreference(context),
        )
    }
}

/** One card of the gate, in ask-order. */
sealed interface PermissionStep {
    /** #1151 — the Screen-events consent (Allow / Don't allow); precedes, and gates, the grant. */
    data object ScreenEvents : PermissionStep
    data class Os(val type: PermissionType) : PermissionStep
}

/** Immutable per-screen state (UDF): the outstanding queue, in ask-order. */
data class PermissionsUiState(
    val missing: List<PermissionType> = emptyList(),
    /** #1151 — the consent is UNDECIDED: the Screen-events step leads the queue. */
    val screenEventsDue: Boolean = false,
    /**
     * #1151 review TT4 — the consent has been READ (non-null). Until then the queue is EMPTY and
     * nothing is "all granted": an OS card must never be walked through ahead of the Screen-events
     * step only because the store read had not landed yet.
     */
    val consentRead: Boolean = false,
) {
    /** The cards, head first: the Screen-events step (if due), then the OS permissions. */
    val steps: List<PermissionStep>
        get() = if (!consentRead) {
            emptyList()
        } else {
            listOfNotNull(PermissionStep.ScreenEvents.takeIf { screenEventsDue }) +
                missing.map { PermissionStep.Os(it) }
        }

    /** Every step answered — only ever true once the consent was read. */
    val allGranted: Boolean get() = consentRead && steps.isEmpty()
}

/**
 * #1151 — the accessibility grant is offered ONLY after a Screen-events decision (dev ruling
 * 2026-09-30: the consent comes BEFORE the service can recognize anything), and never to a debug
 * build that declined (that build's shell is replaced by `DebugEventReceiptShell` anyway).
 */
fun accessibilityOffered(consent: EventReceiptConsent?, isDebugBuild: Boolean): Boolean = when (consent) {
    null, EventReceiptConsent.UNDECIDED -> false
    EventReceiptConsent.DECLINED -> !isDebugBuild
    EventReceiptConsent.ALLOWED -> true
}

/** Pure assembly of one poll + the consent into the gate's state. */
fun buildPermissionsUiState(
    poll: OsPermissionPoll,
    consent: EventReceiptConsent?,
    isDebugBuild: Boolean,
): PermissionsUiState = PermissionsUiState(
    missing = missingPermissions(
        accessibilityGranted = poll.accessibility,
        accessibilityOffered = accessibilityOffered(consent, isDebugBuild),
        notificationListenerGranted = poll.notificationListener,
        locationGranted = poll.location,
        postNotificationsGranted = poll.postNotifications,
        bubblesGranted = poll.bubbles,
    ),
    screenEventsDue = consent == EventReceiptConsent.UNDECIDED,
    consentRead = consent != null,
)

/**
 * Pure projection of one poll (five booleans) onto the outstanding queue — testable without
 * Android. The order is the **ask-order** and is behavioural: the sheet shows the head of this
 * list, one card at a time, so it is the order the user is walked through. The accessibility step
 * is queued only when [accessibilityOffered] (#1151).
 */
fun missingPermissions(
    accessibilityGranted: Boolean,
    accessibilityOffered: Boolean,
    notificationListenerGranted: Boolean,
    locationGranted: Boolean,
    postNotificationsGranted: Boolean,
    bubblesGranted: Boolean,
): List<PermissionType> = buildList {
    if (accessibilityOffered && !accessibilityGranted) add(PermissionType.Accessibility)
    if (!notificationListenerGranted) add(PermissionType.NotificationListener)
    if (!locationGranted) add(PermissionType.Location)
    if (!postNotificationsGranted) add(PermissionType.PostNotifications)
    if (!bubblesGranted) add(PermissionType.Bubbles)
}
