package cloud.trotter.dashbuddy.ui.bubble

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.RecordingTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import timber.log.Timber

/** #916: shareable lifecycle diagnostics preserve notification behavior and exclude chat PII. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BubbleLifecycleLogTest {
    private val tree = RecordingTree()
    private lateinit var notificationManager: NotificationManager
    private lateinit var manager: BubbleManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        notificationManager = mock()
        val chatRepository: ChatRepository = mock()
        val stateManager: StateManagerV2 = mock()
        whenever(stateManager.state).thenReturn(MutableStateFlow(AppState()))
        manager = BubbleManager(context, notificationManager, chatRepository, dagger.Lazy { stateManager })
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
    }

    @Test
    fun `session start and end each emit one Bubble INFO line with registry wire and session id`() {
        manager.startSession("session-916", Platform.WalmartSpark.name)
        manager.endSession(Platform.WalmartSpark.name, "session-916")

        assertEquals(
            listOf(
                info("session start platform=walmart_spark sessionId=session-916"),
                info("session end platform=walmart_spark sessionId=session-916"),
            ),
            tree.records.filter { it.message.startsWith("session ") },
        )
    }

    @Test
    fun `session end is null safe`() {
        manager.endSession()

        assertEquals(
            listOf(info("session end platform=null sessionId=null")),
            tree.records.filter { it.message.startsWith("session end") },
        )
    }

    @Test
    fun `successful bubble post logs requested then returned and attaches dismissal broadcast`() {
        manager.postWelcomeMessage()

        assertEquals(
            listOf(info("bubble post requested id=1 canBubble=true"), info("bubble post returned id=1")),
            bubbleRecords(),
        )
        val notification = argumentCaptor<Notification>()
        verify(notificationManager).notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), notification.capture())
        assertNotNull(NotificationCompat.getBubbleMetadata(notification.firstValue))
        val deleteIntent = notification.firstValue.deleteIntent
        assertNotNull(deleteIntent)
        val broadcast = shadowOf(deleteIntent)
        assertTrue(broadcast.isBroadcast)
        assertEquals(BubbleDismissReceiver::class.java.name, broadcast.savedIntent.component?.className)
        assertTrue(broadcast.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(broadcast.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test
    fun `dismiss receiver emits one Bubble INFO line`() {
        val context = RuntimeEnvironment.getApplication()
        BubbleDismissReceiver().onReceive(context, Intent(context, BubbleDismissReceiver::class.java))

        assertEquals(listOf(info("bubble dismissed by user id=1")), tree.records)
    }

    @Test
    fun `Bubble lines never contain message text persona display name or merchant text`() {
        val merchant = "Fixture Merchant 916"
        val persona = ChatPersona.Customer("Fixture Customer 916")
        val message = "Pickup: $merchant for ${persona.displayName}"
        manager.startSession("session-916", Platform.DoorDash.wire)
        manager.postMessage(message, persona, sessionId = "session-916")
        manager.endSession(Platform.DoorDash.wire, "session-916")
        BubbleDismissReceiver().onReceive(RuntimeEnvironment.getApplication(), Intent())

        val records = bubbleRecords()
        assertTrue(records.isNotEmpty())
        for (record in records) {
            assertEquals(Log.INFO, record.priority)
            for (sensitiveText in listOf(message, persona.displayName, merchant)) {
                assertFalse(record.message.contains(sensitiveText))
            }
        }
    }

    @Test
    fun `failed bubble post logs only exception class and rethrows the same throwable`() {
        assertPostFailure(SecurityException("Fixture Merchant 916"))
    }

    @Test
    fun `bubble post cancellation is logged and rethrown unchanged`() {
        assertPostFailure(CancellationException("Fixture Customer 916"))
    }

    @Test
    fun `bubble post Error is logged and rethrown unchanged`() {
        assertPostFailure(LinkageError("Fixture Merchant 916"))
    }

    private fun assertPostFailure(failure: Throwable) {
        doThrow(failure).whenever(notificationManager).notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), any())

        val thrown = assertThrows(Throwable::class.java) { manager.postWelcomeMessage() }

        assertSame(failure, thrown)
        assertEquals(
            listOf(
                info("bubble post requested id=1 canBubble=true"),
                RecordingTree.Record(Log.ERROR, "Bubble", "bubble post FAILED id=1 cause=${failure.javaClass.simpleName}"),
            ),
            bubbleRecords(),
        )
    }

    private fun info(message: String) = RecordingTree.Record(Log.INFO, "Bubble", message)

    private fun bubbleRecords() = tree.records.filter { it.tag == "Bubble" }
}
