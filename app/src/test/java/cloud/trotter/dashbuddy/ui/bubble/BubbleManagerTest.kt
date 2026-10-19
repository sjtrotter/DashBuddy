package cloud.trotter.dashbuddy.ui.bubble

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.after
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BubbleManagerTest {
    private val sessionId = "starting-dash"
    private val vehicle = Vehicle("active", "Car", 1L)
    private val clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)

    private class Fixture(
        val manager: BubbleManager, val chat: ChatRepository, val notifications: NotificationManager,
    )

    private fun fixture(
        enabled: Boolean = true, active: Vehicle? = vehicle, readings: List<VehicleOdometerReading> = emptyList(),
    ): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val notifications: NotificationManager = mock()
        val chat: ChatRepository = mock()
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val odometer: OdometerReadingRepository = mock()
        val state: StateManagerV2 = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(enabled))
        whenever(garage.garage).thenReturn(flowOf(Garage(listOfNotNull(active), active?.id, readable = true)))
        whenever(odometer.readings).thenReturn(flowOf(readings))
        return Fixture(BubbleManager(context, notifications, chat, dagger.Lazy { state },
            preferences, garage, odometer, clock), chat, notifications)
    }

    private fun reading(at: Long, vehicleId: String = vehicle.id, sequence: Long = 1L) =
        VehicleOdometerReading(vehicleId, LastOdometerReading(52_388, 100.0, at), sequence)

    private fun assertCue(fixture: Fixture) {
        fixture.manager.startSession(sessionId, Platform.DoorDash.name)
        val notifications = argumentCaptor<Notification>()
        verify(fixture.notifications, timeout(5_000).times(2))
            .notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), notifications.capture())
        val texts = argumentCaptor<String>()
        verifyBlocking(fixture.chat, after(100).times(1)) {
            saveMessage(eq(sessionId), texts.capture(), eq(ChatPersona.Dispatcher))
        }
        verifyNoMoreInteractions(fixture.chat)
        assertTrue(texts.firstValue.startsWith("Started "))
        assertEquals(listOf(texts.firstValue, "Log your odometer"), notifications.allValues.map {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)!!
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.bubble_notification_conversation_title),
                style.conversationTitle.toString())
            style.messages.single().text.toString()
        })
    }

    private fun assertNoCue(fixture: Fixture) {
        fixture.manager.startSession(sessionId, Platform.DoorDash.name)
        verifyBlocking(fixture.chat, timeout(5_000)) { saveMessage(eq(sessionId), any(), eq(ChatPersona.Dispatcher)) }
        verifyBlocking(fixture.chat, after(500).times(1)) {
            saveMessage(eq(sessionId), any(), eq(ChatPersona.Dispatcher))
        }
        val texts = argumentCaptor<String>()
        verifyBlocking(fixture.chat) {
            saveMessage(eq(sessionId), texts.capture(), eq(ChatPersona.Dispatcher))
        }
        assertTrue(texts.firstValue.startsWith("Started "))
        verify(fixture.notifications, after(100).times(1)).notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), any())
    }

    @Test fun `a session start with no recent reading posts the cue without saving a chat row`() {
        assertCue(fixture(readings = listOf(reading(clock.millis() - 3_600_000L))))
    }

    @Test fun `a reading in the last ten minutes posts none`() {
        val now = clock.millis()
        // Deliberately unsorted: the active vehicle's latest reading owns the decision.
        assertNoCue(fixture(readings = listOf(reading(now - 60_000L, sequence = 2L), reading(now - 3_600_000L))))
    }

    @Test fun `a reading exactly ten minutes before the injected clock posts none`() {
        assertNoCue(fixture(readings = listOf(reading(clock.millis() - 600_000L))))
    }

    @Test fun `a reading older than ten minutes on the injected clock posts the cue`() {
        assertCue(fixture(readings = listOf(reading(clock.millis() - 600_001L))))
    }

    @Test fun `pref off posts none`() {
        assertNoCue(fixture(enabled = false))
    }

    @Test fun `no vehicle yet posts the odometer line`() {
        assertCue(fixture(active = null))
    }

    @Test fun `a recent reading for another vehicle does not suppress the line`() {
        assertCue(fixture(readings = listOf(reading(clock.millis(), vehicleId = "other"))))
    }
}
