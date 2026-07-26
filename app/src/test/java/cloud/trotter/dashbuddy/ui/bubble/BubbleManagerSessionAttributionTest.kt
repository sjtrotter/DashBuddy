package cloud.trotter.dashbuddy.ui.bubble

import android.app.NotificationManager
import cloud.trotter.dashbuddy.core.data.chat.ChatRepository
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.activeSessionId
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * #867 (write-side): a chat line must be filed to the session it CAME FROM, not to whichever
 * platform happened to produce the last recognized frame.
 *
 * [BubbleManager.postMessage]/[BubbleManager.postOfferNotification] used to save every message to
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

    private fun buildFixture(): Fixture {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val notificationManager: NotificationManager = mock()
        val chatRepository: ChatRepository = mock()
        val stateManager: StateManagerV2 = mock()
        val state = MutableStateFlow(stateWithDoorDashActive())
        whenever(stateManager.state).thenReturn(state)
        val lazyStateManager = dagger.Lazy<StateManagerV2> { stateManager }
        val manager = BubbleManager(context, notificationManager, chatRepository, lazyStateManager)
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
}
