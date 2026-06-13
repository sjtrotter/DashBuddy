package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The dash-id derivation BubbleManager's `activeSessionId` reads (#437). The
 * recovery property follows directly: snapshots restore [AppState], so a
 * restored mid-dash process derives its session id instead of waiting for a
 * StartSession effect that recovery suppresses.
 */
class ActiveSessionIdTest {

    private fun region(platform: Platform, sessionId: String?) = PlatformRegion(
        platform = platform,
        mode = if (sessionId != null) Mode.Online else Mode.Offline,
        session = sessionId?.let { Session(it, startedAt = 100L) },
    )

    @Test
    fun `mid-session state derives the session id - the crash-recovery case`() {
        val state = AppState(regions = Regions(
            platforms = mapOf(Platform.DoorDash to region(Platform.DoorDash, "sess-42")),
        ))
        assertEquals("sess-42", state.activeSessionId())
    }

    @Test
    fun `no live session derives null`() {
        assertNull(AppState().activeSessionId())
        val offline = AppState(regions = Regions(
            platforms = mapOf(Platform.DoorDash to region(Platform.DoorDash, sessionId = null)),
        ))
        assertNull(offline.activeSessionId())
    }

    @Test
    fun `the flow region's active platform wins when several sessions exist`() {
        val state = AppState(regions = Regions(
            flow = FlowRegion(activePlatform = Platform.Uber),
            platforms = mapOf(
                Platform.DoorDash to region(Platform.DoorDash, "dd-sess"),
                Platform.Uber to region(Platform.Uber, "uber-sess"),
            ),
        ))
        assertEquals("uber-sess", state.activeSessionId())
    }

    @Test
    fun `an active platform without a session falls back to the live one`() {
        val state = AppState(regions = Regions(
            flow = FlowRegion(activePlatform = Platform.Uber),
            platforms = mapOf(
                Platform.Uber to region(Platform.Uber, sessionId = null),
                Platform.DoorDash to region(Platform.DoorDash, "dd-sess"),
            ),
        ))
        assertEquals("dd-sess", state.activeSessionId())
    }
}
