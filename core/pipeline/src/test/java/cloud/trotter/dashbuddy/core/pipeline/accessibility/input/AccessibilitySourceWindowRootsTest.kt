package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
// sdk 36: the node mapper reads AccessibilityNodeInfo.getChecked() (API 36) — the #1148 snapshot
// tests map real (mocked) roots, same as AccessibilityNodeMapperPropertyTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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

    // ── #1148 D4: WindowContext on every snapshot + window-specific snapshots ──

    private fun windowInfo(
        windowId: Int,
        node: AccessibilityNodeInfo?,
        active: Boolean,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        windowLayer: Int = windowId,
    ): AccessibilityWindowInfo = mock {
        on { id } doReturn windowId
        on { root } doReturn node
        on { isActive } doReturn active
        on { isFocused } doReturn active
        on { type } doReturn windowType
        on { layer } doReturn windowLayer
    }

    private fun nodeOf(pkg: String): AccessibilityNodeInfo = mock { on { packageName } doReturn pkg }

    @Test
    fun `getCurrentRootSnapshot fills windowContext from the active window`() {
        val activeRoot = nodeOf("com.doordash.driverapp")
        val other = nodeOf("cloud.trotter.dashbuddy")
        val windowList = listOf(windowInfo(7, other, active = false), windowInfo(9, activeRoot, active = true))
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { windows } doReturn windowList
        }

        val snapshot = sourceFor(service).getCurrentRootSnapshot()

        assertNotNull(snapshot)
        val ctx = requireNotNull(snapshot!!.windowContext) { "active-root snapshot must carry its WindowContext" }
        assertEquals(9, ctx.windowId)
        assertEquals(true, ctx.isActive)
        assertEquals(2, ctx.totalWindowCount)
        assertEquals("com.doordash.driverapp", snapshot.packageName)
    }

    @Test
    fun `topmostWindow picks the highest-layer watched application window`() {
        val bubbleRoot = nodeOf("cloud.trotter.dashbuddy")
        val activityRoot = nodeOf("com.doordash.driverapp")
        val dialogRoot = nodeOf("com.doordash.driverapp")
        val imeRoot = nodeOf("com.doordash.driverapp")
        val windowList = listOf(
            windowInfo(1, bubbleRoot, active = true, windowLayer = 10),
            windowInfo(3, activityRoot, active = false, windowLayer = 2),
            windowInfo(7, dialogRoot, active = false, windowLayer = 5),
            windowInfo(9, imeRoot, active = false, windowType = AccessibilityWindowInfo.TYPE_INPUT_METHOD, windowLayer = 20),
        )
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn bubbleRoot
            on { windows } doReturn windowList
        }
        val source = sourceFor(service)

        val located = requireNotNull(source.topmostWindow { it == "com.doordash.driverapp" })

        assertEquals("highest-layer watched APPLICATION window (the IME is not one)", 7, located.window.id)
        assertSame(dialogRoot, located.root)
        assertEquals(4, located.totalWindowCount)
        val snapshot = requireNotNull(source.getWindowSnapshot(located.window, located.root, located.totalWindowCount))
        assertEquals("com.doordash.driverapp", snapshot.packageName)
        assertEquals(7, snapshot.windowContext?.windowId)
        assertEquals(4, snapshot.windowContext?.totalWindowCount)
    }

    @Test
    fun `topmostWindow is null when no application window is watched`() {
        val root = nodeOf("com.android.launcher3")
        val windowList = listOf(windowInfo(1, root, active = true))
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn root
            on { windows } doReturn windowList
        }

        assertNull(sourceFor(service).topmostWindow { it == "com.doordash.driverapp" })
    }
}
