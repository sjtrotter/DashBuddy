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
import cloud.trotter.dashbuddy.domain.model.vehicle.EBike
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import java.io.IOException
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
        readingsFlow: Flow<List<VehicleOdometerReading>> = flowOf(readings),
        readable: Boolean = true,
    ): Fixture {
        val context = RuntimeEnvironment.getApplication()
        val notifications: NotificationManager = mock()
        val chat: ChatRepository = mock()
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val odometer: OdometerReadingRepository = mock()
        val state: StateManagerV2 = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(enabled))
        whenever(garage.garage).thenReturn(flowOf(Garage(listOfNotNull(active), active?.id, readable = readable)))
        whenever(odometer.readings).thenReturn(readingsFlow)
        return Fixture(BubbleManager(context, notifications, chat, dagger.Lazy { state },
            preferences, garage, odometer, clock), chat, notifications)
    }

    private fun reading(at: Long, vehicleId: String = vehicle.id, sequence: Long = 1L) =
        VehicleOdometerReading(vehicleId, LastOdometerReading(52_388, 100.0, at), sequence)

    private fun assertCue(fixture: Fixture, expectedLine: String = "DoorDash shift started in Car") {
        fixture.manager.startSession(sessionId, Platform.DoorDash.name)
        val notifications = argumentCaptor<Notification>()
        verify(fixture.notifications, timeout(5_000).times(2))
            .notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), notifications.capture())
        val texts = argumentCaptor<String>()
        verifyBlocking(fixture.chat, after(100).times(1)) {
            saveMessage(eq(sessionId), texts.capture(), eq(ChatPersona.Dispatcher))
        }
        verifyNoMoreInteractions(fixture.chat)
        assertEquals(expectedLine, texts.firstValue)
        assertEquals(listOf(listOf(texts.firstValue), listOf(texts.firstValue, "Log your odometer")), notifications.allValues.map {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)!!
            assertEquals(RuntimeEnvironment.getApplication().getString(R.string.bubble_notification_conversation_title),
                style.conversationTitle.toString())
            style.messages.map { message -> message.text.toString() }
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
        assertTrue(texts.firstValue.startsWith("DoorDash shift started"))
        val notification = argumentCaptor<Notification>()
        verify(fixture.notifications, after(100).times(1)).notify(eq(BubbleManager.BUBBLE_NOTIFICATION_ID), notification.capture())
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification.firstValue)!!
        assertEquals(listOf(texts.firstValue), style.messages.map { it.text.toString() })
    }

    @Test fun `vehicle start and switch lines never log the vehicle name even at debug`() {
        val name = "PrivateVehicleName1360"
        val messages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val tree = object : timber.log.Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { messages.add(message) }
        }
        timber.log.Timber.plant(tree)
        try {
            val fixture = fixture(enabled = false, active = vehicle.copy(name = name))
            fixture.manager.startSession(sessionId, Platform.DoorDash.name)
            verifyBlocking(fixture.chat, timeout(5_000)) {
                saveMessage(sessionId, "DoorDash shift started in $name", ChatPersona.Dispatcher)
            }
            fixture.manager.postVehicleLine(sessionId, "This shift: $name")
            verifyBlocking(fixture.chat, timeout(5_000)) {
                saveMessage(sessionId, "This shift: $name", ChatPersona.Dispatcher)
            }
            assertTrue(messages.none { name in it })
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test fun `an active e-bike posts only the shift started line`() {
        assertNoCue(fixture(active = vehicle.copy(profile = EBike())))
    }

    @Test fun `an active e-bike with its switch on posts the odometer line`() {
        assertCue(fixture(active = vehicle.copy(profile = EBike(), odometerShown = true)))
    }

    @Test fun `a car with its switch off posts only the shift started line`() {
        assertNoCue(fixture(active = vehicle.copy(odometerShown = false)))
    }

    @Test fun `an unreadable garage posts the shift started line only`() {
        assertNoCue(fixture(active = null, readable = false))
    }

    @Test fun `a failing readings read skips the cue and keeps the shift started line`() {
        assertNoCue(fixture(readingsFlow = flow { throw IOException("read failed") }))
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
        assertCue(fixture(active = null), expectedLine = "DoorDash shift started")
    }

    @Test fun `a recent reading for another vehicle does not suppress the line`() {
        assertCue(fixture(readings = listOf(reading(clock.millis(), vehicleId = "other"))))
    }
}
