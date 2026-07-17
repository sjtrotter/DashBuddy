package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * #788 — [AccessibilitySource.getLiveWindowRoots] must dedup the active-window
 * root, which the platform enumerates BOTH as `rootInActiveWindow` and again
 * inside `service.windows`. Without deduping, the correct click target appears
 * twice, ties with itself, and the fail-closed disambiguator in
 * `UiInteractionHandler` aborts the tap — the field-confirmed #788 bug.
 *
 * Dedup is by `==` (`AccessibilityNodeInfo.equals` = `windowId` + `sourceNodeId`,
 * so the two fetches of the same active-window root are equal). Mockito mocks fall
 * back to reference `equals`, so the same mock instance handed to both
 * `rootInActiveWindow` and a window's `root` models "same underlying node".
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilitySourceWindowRootsTest {

    private fun window(node: AccessibilityNodeInfo?): AccessibilityWindowInfo =
        mock { on { root } doReturn node }

    private fun sourceFor(service: AccessibilityService): AccessibilitySource =
        AccessibilitySource().apply { registerService(service) }

    @Test
    fun `active-window root enumerated twice is deduped, active still first`() {
        val activeRoot = mock<AccessibilityNodeInfo>()
        val otherRoot = mock<AccessibilityNodeInfo>()
        // Build the window mocks up front — creating a mock inside another mock's
        // stubbing lambda trips Mockito's UnfinishedStubbingException.
        // service.windows re-lists the active window's root (the #788 twin) plus a lower window.
        val windowList = listOf(window(activeRoot), window(otherRoot))
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { windows } doReturn windowList
        }

        val roots = sourceFor(service).getLiveWindowRoots()

        assertEquals("the active-window twin must be deduped to one", 2, roots.size)
        assertSame("active-window root stays first (load-bearing ordering)", activeRoot, roots[0])
        assertSame(otherRoot, roots[1])
    }

    @Test
    fun `distinct window roots are all kept, active first`() {
        val activeRoot = mock<AccessibilityNodeInfo>()
        val w1 = mock<AccessibilityNodeInfo>()
        val w2 = mock<AccessibilityNodeInfo>()
        val windowList = listOf(window(activeRoot), window(w1), window(w2))
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { windows } doReturn windowList
        }

        val roots = sourceFor(service).getLiveWindowRoots()

        assertEquals("distinct roots must all survive dedup", 3, roots.size)
        assertSame(activeRoot, roots[0])
    }

    @Test
    fun `no active window falls back to enumerated windows`() {
        val w1 = mock<AccessibilityNodeInfo>()
        val windowList = listOf(window(w1))
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn null
            on { windows } doReturn windowList
        }

        val roots = sourceFor(service).getLiveWindowRoots()

        assertEquals(1, roots.size)
        assertSame(w1, roots[0])
    }
}
