package cloud.trotter.dashbuddy.notice

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The dasher-visible half of #937 — the mirror of `LocaleBoundaryNotifierTest`.
 *
 * Robolectric supplies a real `Context` (the notice needs `getString`); the
 * `NotificationManager` is a mock so the post is directly observable.
 */
@RunWith(RobolectricTestRunner::class)
class RecognitionHealthNotifierTest {

    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        notificationManager = mock()
    }

    private fun notifier() = RecognitionHealthNotifier(
        context = RuntimeEnvironment.getApplication(),
        notificationManager = notificationManager,
    )

    private fun postedText(): String {
        val cap = argumentCaptor<Notification>()
        verify(notificationManager).notify(any<Int>(), cap.capture())
        val n = cap.lastValue
        val title = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        val body = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        return "$title\n$body"
    }

    @Test
    fun `a degraded platform posts one notice on the shared app-notice channel`() {
        notifier().onRecognitionDegraded("doordash", 96)

        verify(notificationManager).createNotificationChannel(any())
        verify(notificationManager).notify(eq(AppNoticeChannel.Ids.RECOGNITION_HEALTH), any<Notification>())
    }

    @Test
    fun `the notice names the platform and the rate - and nothing else`() {
        notifier().onRecognitionDegraded(Platform.DoorDash.wire, 96)

        val text = postedText()
        assertTrue("expected the platform's display name in $text", text.contains("DoorDash"))
        assertTrue("expected the percentage in $text", text.contains("96"))
        // Principle 7 / the Pledges: a liveness notice carries platform facts, never screen text.
        assertFalse("a wire is not user-facing copy", text.contains("doordash"))
    }

    @Test
    fun `an unrecognised wire still notifies, with generic wording`() {
        notifier().onRecognitionDegraded("some_future_platform", 88)

        val text = postedText()
        assertTrue("expected the generic fallback in $text", text.contains("this platform"))
        assertFalse("never fabricate a brand name: $text", text.contains("some_future_platform"))
    }

    @Test
    fun `the app notices do not collide`() {
        assertEquals(
            "every notice on the shared channel needs its own id or they overwrite each other",
            3,
            setOf(
                AppNoticeChannel.Ids.LOCALE_BOUNDARY,
                AppNoticeChannel.Ids.RECOGNITION_HEALTH,
                AppNoticeChannel.Ids.TTS_ENGINE_HEALTH,
            ).size,
        )
    }

    @Test
    fun `a refused post is swallowed - the sensing frame never sees it`() {
        doThrow(SecurityException("POST_NOTIFICATIONS not granted"))
            .whenever(notificationManager).notify(any<Int>(), any<Notification>())

        // Must not throw: this runs on the pipeline's own frame path.
        notifier().onRecognitionDegraded("doordash", 100)
    }
}
