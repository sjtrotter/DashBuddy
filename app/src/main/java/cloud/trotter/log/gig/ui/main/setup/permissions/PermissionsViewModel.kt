package cloud.trotter.log.gig.ui.main.setup.permissions

import android.content.Context
import androidx.lifecycle.ViewModel
import cloud.trotter.log.gig.util.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 * the UI observes an immutable [PermissionsUiState] and dispatches events up.
 *
 * The permission set is OS-owned and can change while the app is backgrounded (the user toggling
 * accessibility in system Settings), so there is nothing to observe reactively — it is *polled* on
 * every edge that can have changed it: construction, the sheet entering composition, every
 * ON_RESUME, and each permission-launcher result. Polling all five on every edge (rather than only
 * the one just requested) keeps a single read path: the OS is the source of truth, never a
 * launcher's echoed boolean. The one thing a launcher result adds (#1457) is whether Android will
 * still show its dialog: a refusal with no rationale routes that step to Android settings.
 *
 * #1457: the steps the dasher put off live in the process-wide [PermissionDeferrals]; this holds
 * no presentation state — which card is on screen, and the exit animation that outlives the last
 * step, belong to the sheet (see [PermissionsBottomSheet]).
 */
@HiltViewModel
class PermissionsViewModel internal constructor(
    private val pollOs: () -> OsPermissionPoll,
    private val eventReceipt: EventReceiptPreferences,
    private val isDebugBuild: Boolean,
    private val deferrals: PermissionDeferrals,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        eventReceipt: EventReceiptPreferences,
        deferrals: PermissionDeferrals,
    ) : this({ OsPermissionPoll.of(context) }, eventReceipt, BuildConfig.DEBUG, deferrals)

    private val osPoll = MutableStateFlow(pollOs())

    /** #1457 — the OS permissions whose dialog Android will no longer show; their card opens settings. */
    private val settingsRouted = MutableStateFlow<Set<PermissionType>>(emptySet())

    /**
     * #1151 (dev re-sequencing 2026-09-30): the OS poll joined with the event-receipt consent, so
     * the Screen-events step leads the queue and gates the accessibility step; #1457 adds the
     * deferrals and the settings routes. Seeded from the current values — never a fabricated
     * "nothing missing" before the first read.
     */
    val uiState: StateFlow<PermissionsUiState> =
        combine(osPoll, eventReceipt.consent, deferrals.deferred, settingsRouted) { poll, consent, deferred, routed ->
            buildPermissionsUiState(poll, consent, isDebugBuild, deferred, routed)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = buildPermissionsUiState(
                osPoll.value,
                eventReceipt.consent.value,
                isDebugBuild,
                deferrals.deferred.value,
                settingsRouted.value,
            ),
        )

    /**
     * Re-poll the OS. Dispatched by the UI when the sheet enters composition, on ON_RESUME, and
     * after a permission-launcher result. Cheap synchronous system-service reads — the same ones
     * that used to run inline in composition. A grant the poll finds (for location, coarse or fine)
     * clears that permission's settings route.
     */
    fun refresh() {
        val poll = pollOs()
        osPoll.value = poll
        settingsRouted.update { routed -> routed.filterTo(mutableSetOf()) { !poll.isGranted(it) } }
    }

    /**
     * #1151 — the Screen-events step's Allow / Don't allow. Writes through the consent's one owner
     * ([EventReceiptPreferences]); either answer is a lasting decision and moves the queue on to the
     * accessibility step (a debug build that declined is replaced by `DebugEventReceiptShell` instead).
     */
    fun onScreenEventsDecision(allow: Boolean) {
        viewModelScope.launch { eventReceipt.set(EventReceiptConsent.of(allow)) }
    }

    /**
     * #1457 — "Not now" on one step (and Don't allow on the screen reading disclosure): puts off that
     * step alone and records nothing.
     */
    fun onDefer(step: PermissionStep) = deferrals.defer(listOf(step))

    /** #1457 — Back, the scrim or a swipe-down: puts off every pending step and records nothing. */
    fun onDismiss() = deferrals.defer(uiState.value.pendingSteps)

    /**
     * #1457 — a runtime permission dialog returned. Refused with no rationale means Android will not
     * show the dialog again (or the dasher dismissed it), so the card offers Android settings instead.
     * Either way the OS is re-polled; the result itself is not trusted as the grant.
     */
    fun onRequestResult(type: PermissionType, granted: Boolean, showRationale: Boolean) {
        if (!granted && !showRationale) settingsRouted.update { it + type }
        refresh()
    }

    /** #1457 — Home's gate card: forget every deferral, so the sheet opens at its first pending step. */
    fun reopen() = deferrals.clear()
}

/** One OS poll of the five required permissions. */
data class OsPermissionPoll(
    val accessibility: Boolean = false,
    val notificationListener: Boolean = false,
    val location: Boolean = false,
    val postNotifications: Boolean = false,
    val bubbles: Boolean = false,
) {
    /** Whether this poll holds [type]'s grant (location: coarse or fine, as [PermissionUtils] reads it). */
    fun isGranted(type: PermissionType): Boolean = when (type) {
        PermissionType.Accessibility -> accessibility
        PermissionType.NotificationListener -> notificationListener
        PermissionType.Location -> location
        PermissionType.PostNotifications -> postNotifications
        PermissionType.Bubbles -> bubbles
    }

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
    /** Its fixed place among the six ("Step N of 6", #1457). */
    val stepNumber: Int

    /** #1151 — the Screen-events consent (Allow / Don't allow); precedes, and gates, the grant. */
    data object ScreenEvents : PermissionStep {
        override val stepNumber: Int get() = 1
    }

    data class Os(val type: PermissionType) : PermissionStep {
        override val stepNumber: Int get() = type.stepNumber
    }
}

/** Immutable per-screen state (UDF): the outstanding queue, in ask-order. */
data class PermissionsUiState(
    /** OS grants not held, in ask-order; accessibility only once it is offered (#1151). */
    val missing: List<PermissionType> = emptyList(),
    /** #1151 — the consent is UNDECIDED: the Screen-events step leads the queue. */
    val screenEventsDue: Boolean = false,
    /**
     * #1151 review TT4 — the consent has been READ (non-null). Until then the queue is EMPTY and
     * nothing is "all granted": an OS card must never be walked through ahead of the Screen-events
     * step only because the store read had not landed yet.
     */
    val consentRead: Boolean = false,
    /** #1457 — steps put off until Home's gate card or the next cold start ([PermissionDeferrals]). */
    val deferred: Set<PermissionStep> = emptySet(),
    /** #1457 — missing permissions whose card opens Android settings instead of the dialog. */
    val settingsRouted: Set<PermissionType> = emptySet(),
) {
    /** The cards still to show, head first: Screen events (if due), then the OS permissions, minus [deferred]. */
    val pendingSteps: List<PermissionStep>
        get() = if (!consentRead) {
            emptyList()
        } else {
            (listOfNotNull(PermissionStep.ScreenEvents.takeIf { screenEventsDue }) +
                missing.map { PermissionStep.Os(it) }) - deferred
        }

    /** Every step answered and granted — only ever true once the consent was read; a deferral never makes it true. */
    val allGranted: Boolean get() = consentRead && !screenEventsDue && missing.isEmpty()
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

/** Pure assembly of one poll + the consent + the deferrals and settings routes into the gate's state. */
fun buildPermissionsUiState(
    poll: OsPermissionPoll,
    consent: EventReceiptConsent?,
    isDebugBuild: Boolean,
    deferred: Set<PermissionStep> = emptySet(),
    settingsRouted: Set<PermissionType> = emptySet(),
): PermissionsUiState {
    val missing = missingPermissions(
        accessibilityGranted = poll.accessibility,
        accessibilityOffered = accessibilityOffered(consent, isDebugBuild),
        notificationListenerGranted = poll.notificationListener,
        locationGranted = poll.location,
        postNotificationsGranted = poll.postNotifications,
        bubblesGranted = poll.bubbles,
    )
    return PermissionsUiState(
        missing = missing,
        screenEventsDue = consent == EventReceiptConsent.UNDECIDED,
        consentRead = consent != null,
        deferred = deferred,
        settingsRouted = settingsRouted.filterTo(mutableSetOf()) { it in missing },
    )
}

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
