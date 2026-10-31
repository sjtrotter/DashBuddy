package cloud.trotter.log.gig.ui.main.setup.permissions

import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #944 — the permission gate's queue projection, hoisted out of composition into
 * [missingPermissions]. It used to be a `remember(five booleans)` block inside
 * `PermissionsBottomSheet`, i.e. untestable; these pin the two things the sheet depends on — that
 * the ask-order is fixed (the sheet renders the head of this list, one card at a time) and that
 * "nothing missing" is a real empty list, which is the edge that closes the gate.
 *
 * #1457 — the gate contract: [PermissionsUiState.pendingSteps] is the outstanding steps minus the
 * deferred ones, a deferral never makes [PermissionsUiState.allGranted] true, deferrals live in the
 * process-wide [PermissionDeferrals] (they outlive the ViewModel, cleared only by a cold start or
 * Home's gate card), and a refused runtime permission with no rationale routes to Android settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsUiStateTest {

    private fun poll(
        accessibility: Boolean = true,
        listener: Boolean = true,
        location: Boolean = true,
        postNotifications: Boolean = true,
        bubbles: Boolean = true,
    ) = missingPermissions(
        accessibilityGranted = accessibility,
        accessibilityOffered = true,
        notificationListenerGranted = listener,
        locationGranted = location,
        postNotificationsGranted = postNotifications,
        bubblesGranted = bubbles,
    )

    @Test
    fun `everything granted leaves an empty queue`() {
        val missing = poll()

        assertEquals(emptyList<PermissionType>(), missing)
        assertTrue(PermissionsUiState(missing, consentRead = true).allGranted)
    }

    @Test
    fun `missing permissions queue in the fixed ask-order`() {
        val missing = poll(
            accessibility = false,
            listener = false,
            location = false,
            postNotifications = false,
            bubbles = false,
        )

        assertEquals(
            listOf(
                PermissionType.Accessibility,
                PermissionType.NotificationListener,
                PermissionType.Location,
                PermissionType.PostNotifications,
                PermissionType.Bubbles,
            ),
            missing,
        )
        // The head is the card the sheet shows first.
        assertEquals(PermissionType.Accessibility, missing.first())
        assertFalse(PermissionsUiState(missing, consentRead = true).allGranted)
    }

    @Test
    fun `granting the head advances the queue to the next card`() {
        val first = poll(accessibility = false, location = false)
        assertEquals(PermissionType.Accessibility, first.first())

        // The re-poll a resume (or launcher result) triggers, with accessibility now granted.
        val second = poll(location = false)

        assertEquals(listOf(PermissionType.Location), second)
    }

    @Test
    fun `each permission maps to its own card`() {
        assertEquals(listOf(PermissionType.Accessibility), poll(accessibility = false))
        assertEquals(listOf(PermissionType.NotificationListener), poll(listener = false))
        assertEquals(listOf(PermissionType.Location), poll(location = false))
        assertEquals(listOf(PermissionType.PostNotifications), poll(postNotifications = false))
        assertEquals(listOf(PermissionType.Bubbles), poll(bubbles = false))
    }

    @Test
    fun `state defaults to nothing missing - but never all-granted before the consent is read (#1151 TT4)`() {
        assertFalse(PermissionsUiState().allGranted)
        assertEquals(emptyList<PermissionType>(), PermissionsUiState().missing)
        assertTrue(PermissionsUiState(consentRead = true).allGranted)
    }

    @Test
    fun `each step has its fixed place among six (#1457)`() {
        assertEquals(1, PermissionStep.ScreenEvents.stepNumber)
        assertEquals(
            listOf(2, 3, 4, 5, 6),
            listOf(
                PermissionType.Accessibility,
                PermissionType.NotificationListener,
                PermissionType.Location,
                PermissionType.PostNotifications,
                PermissionType.Bubbles,
            ).map { PermissionStep.Os(it).stepNumber },
        )
        assertEquals(6, PermissionType.STEP_COUNT)
    }

    // ---- #1457 gate contract (pure) ------------------------------------------------------------

    private val nothingGranted = OsPermissionPoll()
    private val allGranted = OsPermissionPoll(true, true, true, true, true)
    private val a11yStep = PermissionStep.Os(PermissionType.Accessibility)

    @Test
    fun `pending steps are empty before the consent is read, whatever is deferred`() {
        val s = buildPermissionsUiState(nothingGranted, null, isDebugBuild = false, deferred = setOf(a11yStep))
        assertTrue(s.pendingSteps.isEmpty())
        assertFalse(s.allGranted)
    }

    @Test
    fun `a deferred step leaves the pending queue but never counts as granted`() {
        val s = buildPermissionsUiState(
            allGranted.copy(accessibility = false),
            EventReceiptConsent.ALLOWED,
            isDebugBuild = false,
            deferred = setOf(a11yStep),
        )
        assertTrue(s.pendingSteps.isEmpty())
        assertEquals(listOf(PermissionType.Accessibility), s.missing)
        assertFalse("a deferral never makes the gate all-granted", s.allGranted)
    }

    @Test
    fun `a deferred Screen events step is still due, so nothing is all-granted`() {
        val s = buildPermissionsUiState(
            allGranted,
            EventReceiptConsent.UNDECIDED,
            isDebugBuild = false,
            deferred = setOf(PermissionStep.ScreenEvents),
        )
        assertTrue(s.pendingSteps.isEmpty())
        assertTrue(s.screenEventsDue)
        assertFalse(s.allGranted)
    }

    @Test
    fun `a settings route holds only while that permission is missing`() {
        val routed = setOf(PermissionType.Location, PermissionType.PostNotifications)
        val s = buildPermissionsUiState(
            allGranted.copy(postNotifications = false),
            EventReceiptConsent.ALLOWED,
            isDebugBuild = false,
            settingsRouted = routed,
        )
        assertEquals(setOf(PermissionType.PostNotifications), s.settingsRouted)
    }

    // ---- #1457 gate contract (ViewModel + PermissionDeferrals) ---------------------------------

    private class FakePrefs(initial: EventReceiptConsent?) : EventReceiptPreferences {
        override val receipt = MutableStateFlow<ConsentReceipt?>(null)
        private val flow = MutableStateFlow(initial)
        override val consent: StateFlow<EventReceiptConsent?> = flow
        val writes = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent): Boolean {
            writes += consent
            flow.value = consent
            return true
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private var os = nothingGranted

    private fun vm(prefs: EventReceiptPreferences, deferrals: PermissionDeferrals) =
        PermissionsViewModel({ os }, prefs, isDebugBuild = false, deferrals)

    @Test
    fun `screen reading's Don't allow defers only that step and records nothing`() {
        os = allGranted.copy(accessibility = false, bubbles = false)
        val prefs = FakePrefs(EventReceiptConsent.ALLOWED)
        val model = vm(prefs, PermissionDeferrals())
        assertEquals(a11yStep, model.uiState.value.pendingSteps.first())

        model.onDefer(a11yStep)

        assertEquals(listOf<PermissionStep>(PermissionStep.Os(PermissionType.Bubbles)), model.uiState.value.pendingSteps)
        assertTrue("a deferral writes no consent", prefs.writes.isEmpty())
    }

    @Test
    fun `after Don't allow it asks again only from Home or on a cold start`() {
        os = allGranted.copy(accessibility = false)
        val deferrals = PermissionDeferrals()
        val model = vm(FakePrefs(EventReceiptConsent.ALLOWED), deferrals)
        model.onDefer(a11yStep)
        assertTrue(model.uiState.value.pendingSteps.isEmpty())

        // A resume, a launcher result or a trip to Android settings re-polls, and asks nothing.
        model.refresh()
        assertTrue(model.uiState.value.pendingSteps.isEmpty())

        // Home's gate card.
        model.reopen()
        assertEquals(listOf<PermissionStep>(a11yStep), model.uiState.value.pendingSteps)

        // A cold start: a new process, so a new PermissionDeferrals.
        model.onDefer(a11yStep)
        val coldStart = vm(FakePrefs(EventReceiptConsent.ALLOWED), PermissionDeferrals())
        assertEquals(listOf<PermissionStep>(a11yStep), coldStart.uiState.value.pendingSteps)
    }

    @Test
    fun `a deferral of either step survives rotation, navigation and a Settings round trip`() {
        os = nothingGranted
        val deferrals = PermissionDeferrals()
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val first = vm(prefs, deferrals)
        first.onDismiss() // closing the sheet on the Screen events step defers every pending step
        assertTrue(first.uiState.value.pendingSteps.isEmpty())

        // Rotation or navigation: a new ViewModel over the same process-wide deferrals.
        val second = vm(prefs, deferrals)
        assertTrue(second.uiState.value.pendingSteps.isEmpty())
        // A Settings round trip: ON_RESUME re-polls.
        second.refresh()
        assertTrue(second.uiState.value.pendingSteps.isEmpty())
        assertTrue("closing the sheet records nothing", prefs.writes.isEmpty())

        // The other step: screen reading, deferred alone, after a Screen events decision.
        val decided = FakePrefs(EventReceiptConsent.ALLOWED)
        val a11yDeferrals = PermissionDeferrals()
        os = allGranted.copy(accessibility = false)
        vm(decided, a11yDeferrals).onDefer(a11yStep)
        val rotated = vm(decided, a11yDeferrals)
        rotated.refresh()
        assertTrue(rotated.uiState.value.pendingSteps.isEmpty())
        assertFalse(rotated.uiState.value.allGranted)
    }

    @Test
    fun `Not now on the last step closes the queue and keeps allGranted false`() {
        os = allGranted.copy(bubbles = false)
        val model = vm(FakePrefs(EventReceiptConsent.ALLOWED), PermissionDeferrals())
        model.onDefer(PermissionStep.Os(PermissionType.Bubbles))
        assertTrue(model.uiState.value.pendingSteps.isEmpty())
        assertFalse(model.uiState.value.allGranted)
        assertTrue(model.uiState.value.isClosable())
    }

    @Test
    fun `Back and swipe-down defer every pending step and record nothing`() {
        os = nothingGranted
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val deferrals = PermissionDeferrals()
        val model = vm(prefs, deferrals)
        val pending = model.uiState.value.pendingSteps
        assertTrue(pending.isNotEmpty())

        model.onDismiss()

        assertEquals(pending.toSet(), deferrals.deferred.value)
        assertTrue(model.uiState.value.pendingSteps.isEmpty())
        assertTrue("no consent is written", prefs.writes.isEmpty())
        assertEquals(EventReceiptConsent.UNDECIDED, prefs.consent.value)
    }

    @Test
    fun `Screen events' Don't allow is a lasting decision, never a deferral`() {
        os = nothingGranted
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val deferrals = PermissionDeferrals()
        val model = vm(prefs, deferrals)

        model.onScreenEventsDecision(allow = false)

        assertEquals(listOf(EventReceiptConsent.DECLINED), prefs.writes)
        assertTrue(deferrals.deferred.value.isEmpty())
        assertEquals(a11yStep, model.uiState.value.pendingSteps.first())
    }

    @Test
    fun `a refusal with no rationale routes to settings, and the grant clears the route`() {
        os = allGranted.copy(postNotifications = false)
        val model = vm(FakePrefs(EventReceiptConsent.ALLOWED), PermissionDeferrals())

        model.onRequestResult(PermissionType.PostNotifications, granted = false, showRationale = true)
        assertTrue("Android will still ask: no route", model.uiState.value.settingsRouted.isEmpty())

        model.onRequestResult(PermissionType.PostNotifications, granted = false, showRationale = false)
        assertEquals(setOf(PermissionType.PostNotifications), model.uiState.value.settingsRouted)

        os = allGranted
        model.refresh()
        assertTrue(model.uiState.value.settingsRouted.isEmpty())
        assertTrue(model.uiState.value.allGranted)
    }

    @Test
    fun `a coarse-only location grant counts as granted and clears the location route`() {
        os = allGranted.copy(location = false)
        val model = vm(FakePrefs(EventReceiptConsent.ALLOWED), PermissionDeferrals())
        model.onRequestResult(PermissionType.Location, granted = false, showRationale = false)
        assertEquals(setOf(PermissionType.Location), model.uiState.value.settingsRouted)

        // The dasher chose Approximate: the poll reads coarse OR fine as the location grant.
        os = allGranted
        model.onRequestResult(PermissionType.Location, granted = true, showRationale = false)

        assertTrue(model.uiState.value.settingsRouted.isEmpty())
        assertFalse(PermissionType.Location in model.uiState.value.missing)
    }
}
