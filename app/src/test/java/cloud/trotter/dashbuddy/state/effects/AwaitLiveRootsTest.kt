package cloud.trotter.dashbuddy.state.effects

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #602 — bounded re-resolve retry for "No live windows".
 *
 * A notification-action tap can reach [UiInteractionHandler.performVerifiedClick]
 * while a SystemUI takeover (shade/lock) is still covering the platform app, so
 * `getLiveWindowRoots()` briefly returns nothing even though the window is about
 * to reappear ~0.5-1s later. [awaitLiveRoots] retries the (already
 * package-scoped) source lambda across bounded delays instead of failing
 * closed on the very first empty read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitLiveRootsTest {

    @Test
    fun `retries with the configured delays until a non-empty roots list appears`() = runTest {
        var callCount = 0
        val liveRoots = listOf(mock<AccessibilityNodeInfo>())

        val result = awaitLiveRoots("com.doordash.driverapp") {
            callCount++
            if (callCount <= 2) emptyList() else liveRoots
        }

        assertSame(liveRoots, result)
        // 1 initial call + 2 empty retries before the 3rd call succeeds.
        assertEquals(3, callCount)
        // Only the first two retry delays (300ms, 500ms) were consumed.
        assertEquals(300L + 500L, currentTime)
    }

    @Test
    fun `an immediately non-empty source consumes no delay at all`() = runTest {
        var callCount = 0
        val liveRoots = listOf(mock<AccessibilityNodeInfo>())

        val result = awaitLiveRoots("com.doordash.driverapp") {
            callCount++
            liveRoots
        }

        assertSame(liveRoots, result)
        assertEquals(1, callCount)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `returns empty after the full retry budget when the window never reappears`() = runTest {
        var callCount = 0

        val result = awaitLiveRoots("com.doordash.driverapp") {
            callCount++
            emptyList()
        }

        assertTrue(result.isEmpty())
        // 1 initial call + 3 retries, all empty.
        assertEquals(4, callCount)
        // All three retry delays (300 + 500 + 700 = 1500ms, the plan's <=1.5s budget) consumed.
        assertEquals(1500L, currentTime)
    }
}
