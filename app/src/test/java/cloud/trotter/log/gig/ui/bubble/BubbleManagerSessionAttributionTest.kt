package cloud.trotter.log.gig.ui.bubble

import android.app.NotificationManager
import cloud.trotter.log.gig.core.data.chat.ChatRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.model.chat.ChatPersona
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.activeSessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * #867 (write-side): a chat line must be filed to the session it CAME FROM, not to whichever
 * platform happened to produce the last recognized frame.
 *
 * [BubbleManager.postMessage]/[BubbleManager.postOfferChat] used to save every message to
 * `activeSessionId.value` **at save time**. `AppState.activeSessionId()` is the session of
 * `regions.flow.activePlatform`, which flips on every app switch while multi-apping — so an Uber
 * offer's outcome line could be written into the DoorDash session's chat history (durable
 * corruption of the per-session drill-down, not just the live view). Both now take an explicit
 * originating session, falling back to the active one only when the caller genuinely has none.
 *
 * Drives the REAL [BubbleManager] with mocked collaborators, the [BubbleManagerChatLogTest]
 * precedent (a `@HiltViewModel`-free Robolectric seam; the effect-side threading is asserted in
 * `:core:state`'s `EffectMapTest`).
 */
@RunWith(RobolectricTestRunner::class)
class BubbleManagerSessionAttributionTest {

    private val doorDashSession = "dd-session-1"
    private val uberSession = "uber-session-2"

    /** Active platform = DoorDash, so `activeSessionId()` resolves to the DoorDash session. */
    private fun stateWithDoorDashActive() = AppState(
        regions = Regions(
            flow = FlowRegion(activePlatform = Platform.DoorDash),
            platforms = mapOf(
                Platform.DoorDash to PlatformRegion(
                    platform = Platform.DoorDash,
                    session = Session(sessionId = doorDashSession, startedAt = 1_000L),
                ),
                Platform.Uber to PlatformRegion(
                    platform = Platform.Uber,
                    session = Session(sessionId = uberSession, startedAt = 2_000L),
                ),
            ),
        ),
    )

    private class Fixture(val manager: BubbleManager, val chatRepository: ChatRepository)

    private fun buildFixture(savedGarage: Garage = Garage(emptyList(), null, readable = true)): Fixture {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val notificationManager: NotificationManager = mock()
        val chatRepository: ChatRepository = mock()
        val preferences: AppPreferencesRepository = mock()
        val garage: GarageRepository = mock()
        val readings: OdometerReadingRepository = mock()
        whenever(preferences.odometerPrompts).thenReturn(flowOf(false))
        whenever(garage.garage).thenReturn(flowOf(savedGarage))
        whenever(readings.readings).thenReturn(flowOf(emptyList()))
        val stateManager: StateManagerV2 = mock()
        val state = MutableStateFlow(stateWithDoorDashActive())
        whenever(stateManager.state).thenReturn(state)
        val lazyStateManager = dagger.Lazy<StateManagerV2> { stateManager }
        val manager = BubbleManager(context, notificationManager, chatRepository, lazyStateManager, preferences, garage, readings,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        // The derived flow is Eagerly-started on an IO scope; await it so the fallback assertions
        // race nothing (an un-emitted flow would read null, not the DoorDash session).
        runBlocking { withTimeout(5_000) { manager.activeSessionId.first { it != null } } }
        return Fixture(manager, chatRepository)
    }

    @Test
    fun `the fixture's active session is the DoorDash one — the mis-filing target`() {
        assertEquals(doorDashSession, stateWithDoorDashActive().activeSessionId())
    }

    @Test
    fun `a message with an explicit session saves to THAT session, not the active one`() {
        val (manager, chatRepository) = buildFixture().let { it.manager to it.chatRepository }

        manager.postMessage(
            text = "Offer Declined",
            persona = ChatPersona.Dispatcher,
            sessionId = uberSession,
        )

        // The Uber offer's outcome lands in the UBER dash's chat even though DoorDash owns the
        // screen (and therefore `activeSessionId`) at save time.
        verifyBlocking(chatRepository, timeout(5_000)) {
            saveMessage(uberSession, "Offer Declined", ChatPersona.Dispatcher)
        }
    }

    @Test
    fun `a session-less message still falls back to the active session`() {
        val (manager, chatRepository) = buildFixture().let { it.manager to it.chatRepository }

        // A genuinely session-less caller (rule-declared bubble, tip push, welcome line).
        manager.postMessage(text = "Welcome", persona = ChatPersona.Dispatcher)

        verifyBlocking(chatRepository, timeout(5_000)) {
            saveMessage(doorDashSession, "Welcome", ChatPersona.Dispatcher)
        }
    }

    @Test
    fun `startSession files its chat copy to the STARTING session`() {
        val (manager, chatRepository) = buildFixture().let { it.manager to it.chatRepository }

        // The Uber dash just started while DoorDash still owns the screen.
        manager.startSession(uberSession, Platform.Uber.name)

        verifyBlocking(chatRepository, timeout(5_000)) {
            saveMessage(eq(uberSession), any(), any())
        }
    }

    @Test fun `start line names the readable active vehicle`() {
        val vehicle = cloud.trotter.log.gig.domain.model.vehicle.Vehicle("malibu", "Malibu", 0)
        val fixture = buildFixture(Garage(listOf(vehicle), vehicle.id, true))
        fixture.manager.startSession(doorDashSession, Platform.DoorDash.name)
        verifyBlocking(fixture.chatRepository, timeout(5_000)) {
            saveMessage(doorDashSession, "DoorDash shift started in Malibu", ChatPersona.Dispatcher)
        }
    }

    @Test fun `start line without an active vehicle keeps existing copy`() {
        val fixture = buildFixture()
        fixture.manager.startSession(doorDashSession, Platform.DoorDash.name)
        verifyBlocking(fixture.chatRepository, timeout(5_000)) {
            saveMessage(doorDashSession, "DoorDash shift started", ChatPersona.Dispatcher)
        }
    }

    @Test fun `start line with an unreadable garage keeps existing copy`() {
        val vehicle = cloud.trotter.log.gig.domain.model.vehicle.Vehicle("malibu", "Malibu", 0)
        val fixture = buildFixture(Garage(listOf(vehicle), vehicle.id, false))
        fixture.manager.startSession(doorDashSession, Platform.DoorDash.name)
        verifyBlocking(fixture.chatRepository, timeout(5_000)) {
            saveMessage(doorDashSession, "DoorDash shift started", ChatPersona.Dispatcher)
        }
    }

    @Test fun `vehicle line uses dispatcher and its originating session`() {
        val fixture = buildFixture()
        fixture.manager.postVehicleLine(uberSession, "This shift: Civic")
        verifyBlocking(fixture.chatRepository, timeout(5_000)) {
            saveMessage(uberSession, "This shift: Civic", ChatPersona.Dispatcher)
        }
    }

    @Test
    fun `endSession files its chat copy to the ENDED session`() {
        val (manager, chatRepository) = buildFixture().let { it.manager to it.chatRepository }

        // The grace-lapsed shape: the Uber dash's end commits while DoorDash owns the screen, and
        // the Uber region's own session is already gone — only the effect knows which dash ended.
        manager.endSession(Platform.Uber.name, sessionId = uberSession)

        verifyBlocking(chatRepository, timeout(5_000)) {
            saveMessage(eq(uberSession), any(), any())
        }
    }
}
