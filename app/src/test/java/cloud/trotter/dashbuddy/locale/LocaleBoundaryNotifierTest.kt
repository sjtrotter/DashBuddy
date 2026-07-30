package cloud.trotter.dashbuddy.locale

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.test.util.RecordingTree
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber
import java.util.Locale

/**
 * The side-effecting half of #938: WARN every non-English sensor start, interrupt the dasher
 * exactly once.
 *
 * Robolectric supplies a real `Context` (the notice needs `getString`); the `NotificationManager`
 * and `AppStateRepository` are mocks so the flag write and the post are directly observable.
 */
@RunWith(RobolectricTestRunner::class)
class LocaleBoundaryNotifierTest {

    private lateinit var tree: RecordingTree
    private lateinit var notificationManager: NotificationManager
    private lateinit var appState: AppStateRepository

    @Before
    fun setUp() {
        tree = RecordingTree()
        Timber.plant(tree)
        notificationManager = mock()
        appState = mock()
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    /** The WARN lines this component emitted, in order. */
    private fun warnings(): List<String> =
        tree.records.filter { it.priority == Log.WARN }.map { it.message }

    private fun notifier(alreadyNotified: Boolean): LocaleBoundaryNotifier {
        whenever(appState.localeBoundaryNoticeShown).thenReturn(MutableStateFlow(alreadyNotified))
        return LocaleBoundaryNotifier(
            context = RuntimeEnvironment.getApplication(),
            notificationManager = notificationManager,
            appStateRepository = appState,
            scope = TestScope(),
        )
    }

    // =========================================================================
    // The happy (English) path is silent
    // =========================================================================

    @Test
    fun `an english device neither warns nor notifies`() = runTest {
        notifier(alreadyNotified = false).report(Locale.US)

        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
        verify(appState, never()).setLocaleBoundaryNoticeShown()
        assertTrue(
            "an English device must produce no locale WARN, ${warnings()}",
            warnings().isEmpty(),
        )
    }

    // =========================================================================
    // First non-English start: warn + notify + remember
    // =========================================================================

    @Test
    fun `a first non-english start warns, posts the notice and records it`() = runTest {
        notifier(alreadyNotified = false).report(Locale.forLanguageTag("es-MX"))

        verify(notificationManager).createNotificationChannel(any())
        verify(notificationManager).notify(eq(LocaleBoundaryNotifier.NOTIFICATION_ID), any<Notification>())
        verify(appState).setLocaleBoundaryNoticeShown()
        assertEquals(1, warnings().size)
    }

    @Test
    fun `the WARN carries only the iso-639 language code - principle 7`() = runTest {
        notifier(alreadyNotified = false).report(Locale.forLanguageTag("es-MX"))

        val warn = warnings().single()
        assertTrue("expected the language code in $warn", warn.contains("'es'"))
        // The region is device-identifying detail the log has no use for.
        assertTrue("the WARN must not carry the region: $warn", !warn.contains("MX"))
        assertTrue("expected the issue reference in $warn", warn.contains("#938"))
    }

    // =========================================================================
    // Repeat starts: still warn, never interrupt twice
    // =========================================================================

    @Test
    fun `a repeat non-english start warns again but does not re-notify`() = runTest {
        notifier(alreadyNotified = true).report(Locale.forLanguageTag("fr"))

        assertEquals(1, warnings().size)
        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
        verify(appState, never()).setLocaleBoundaryNoticeShown()
    }

    // =========================================================================
    // Fail-open
    // =========================================================================

    @Test
    fun `a flag read failure is swallowed - sensing is never blocked`() = runTest {
        whenever(appState.localeBoundaryNoticeShown)
            .thenReturn(flow { throw IllegalStateException("datastore is unhappy") })
        val subject = LocaleBoundaryNotifier(
            context = RuntimeEnvironment.getApplication(),
            notificationManager = notificationManager,
            appStateRepository = appState,
            scope = TestScope(),
        )

        subject.report(Locale.forLanguageTag("es")) // must not throw

        verify(notificationManager, never()).notify(any<Int>(), any<Notification>())
    }

    @Test
    fun `a failed post leaves the flag unset so a later start retries`() = runTest {
        doThrow(SecurityException("POST_NOTIFICATIONS not granted"))
            .whenever(notificationManager).notify(any<Int>(), any<Notification>())

        notifier(alreadyNotified = false).report(Locale.forLanguageTag("es")) // must not throw

        verify(appState, never()).setLocaleBoundaryNoticeShown()
    }
}
