package cloud.trotter.dashbuddy.state.effects

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // Verify recycling on API 30, where it is required.
class ScreenShotHandlerBoundaryTest {
    private val service = mock<AccessibilityService>()
    private val source = mock<AccessibilitySource>()
    private val context = mock<Context>()
    private val preferences = mock<PlatformPreferences>()
    private val enabled = MutableStateFlow(setOf(Platform.DoorDash, Platform.Uber))
    private val effect = AppEffect.CaptureScreenshot(filenamePrefix = "offer", category = null)

    @Test
    fun `clean single DoorDash window captures only after settle and recycles root`() {
        val root = root()
        checkCapture(listOf(window(root)), captures = true)
        verify(root).recycle()
    }

    @Test
    fun `own bubble and system bars above delivery app allow capture`() {
        val bubble = root("cloud.trotter.dashbuddy")
        checkCapture(
            listOf(
                window(bubble, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY),
                window(null, AccessibilityWindowInfo.TYPE_SYSTEM),
                window(root()),
            ),
            captures = true,
        )
        verify(bubble).recycle()
        verify(bubble, never()).getChild(any())
    }

    @Test
    fun `toggle revoked during settle skips`() {
        checkCapture(listOf(window(root())), beforeFire = { allowed = false })
    }

    @Test
    fun `platform disabled during settle skips`() {
        checkCapture(listOf(window(root())), beforeFire = { enabled.value = emptySet() })
    }

    @Test
    fun `browser appearing during settle skips`() {
        checkCapture(listOf(window(root())), beforeFire = {
            val sharedWindows = listOf(window(root()), window(root("com.android.chrome")))
            whenever(service.windows).thenReturn(sharedWindows)
        })
    }

    @Test
    fun `no windows skips`() = checkCapture(emptyList())

    @Test
    fun `unreadable root skips`() = checkCapture(listOf(window(null)))

    @Test
    fun `keyboard skips`() = checkCapture(
        listOf(window(root()), window(root(), AccessibilityWindowInfo.TYPE_INPUT_METHOD)),
    )

    @Test
    fun `unfocused Uber overlay over Maps skips`() = checkCapture(
        listOf(
            window(root(Platform.Uber.packageName), AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY),
            window(root("com.google.android.apps.maps")),
        ),
    )

    @Test
    fun `foreign overlay skips without inspecting its content`() {
        val foreign = root("foreign.app")
        checkCapture(listOf(window(root()), window(foreign, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)))
        verify(foreign, never()).getChild(any())
        verify(foreign).recycle()
    }

    @Test
    fun `external display delivery app does not authorize phone banking`() = checkCapture(
        listOf(window(root(), displayId = 1), window(root("bank.app"))),
    )

    @Test
    fun `external display alone skips`() = checkCapture(listOf(window(root(), displayId = 1)))

    @Test
    fun `sensitive child skips and is recycled`() {
        val child = root()
        whenever(child.text).thenReturn("bank account")
        val root = root(child = child)
        checkCapture(listOf(window(root)))
        verify(child).recycle()
        verify(root).recycle()
    }

    @Test
    fun `null child in an otherwise readable tree skips`() {
        val root = root()
        whenever(root.childCount).thenReturn(1)
        checkCapture(listOf(window(root)))
        verify(root).recycle()
    }

    @Test
    fun `capture depth budget rejects partial tree and recycles rejected child`() {
        val deepest = root()
        var tree = deepest
        repeat(41) { tree = root(child = tree) }
        checkCapture(listOf(window(tree)))
        verify(deepest).recycle()
        verify(tree).recycle()
    }

    @Test
    fun `capture node budget rejects wide tree`() {
        val root = root()
        whenever(root.childCount).thenReturn(1_500)
        whenever(root.getChild(any())).thenAnswer { root() }
        checkCapture(listOf(window(root)))
        verify(root, never()).getChild(1_499)
        verify(root).recycle()
    }

    @Test
    fun `mapping exception skips and recycles child and root`() {
        val child = root()
        whenever(child.text).thenThrow(IllegalStateException("unreadable"))
        val root = root(child = child)
        checkCapture(listOf(window(root)))
        verify(child).recycle()
        verify(root).recycle()
    }

    @Test
    fun `root exception alongside clean window skips`() {
        val unreadable = window(null)
        whenever(unreadable.root).thenThrow(IllegalStateException("unreadable"))
        checkCapture(listOf(window(root()), unreadable))
    }

    @Test
    fun `window metadata exception alongside clean window skips`() {
        val unreadable = window(root())
        whenever(unreadable.displayId).thenThrow(IllegalStateException("unreadable"))
        checkCapture(listOf(window(root()), unreadable))
    }

    @Test
    fun `enumeration exception skips`() {
        checkCapture(emptyList(), beforeFire = {
            whenever(service.windows).thenThrow(IllegalStateException("unreadable"))
        })
    }

    private var allowed = true

    private fun checkCapture(
        windows: List<AccessibilityWindowInfo>,
        captures: Boolean = false,
        beforeFire: () -> Unit = {},
    ) = runTest {
        whenever(service.windows).thenReturn(windows)
        whenever(source.getService()).thenReturn(service)
        whenever(context.packageName).thenReturn("cloud.trotter.dashbuddy")
        whenever(preferences.enabledPlatforms).thenReturn(enabled)
        val handler = ScreenShotHandler(context, source, preferences, StandardTestDispatcher(testScheduler))
        handler.capture(this, effect) { allowed }
        runCurrent()
        advanceTimeBy(ScreenShotHandler.SETTLE_MS - 1)
        runCurrent()
        verify(service, never()).takeScreenshot(any(), any(), any())
        beforeFire()
        advanceTimeBy(2)
        runCurrent()
        if (captures) {
            verify(service).takeScreenshot(eq(Display.DEFAULT_DISPLAY), any(), any())
        } else {
            verify(service, never()).takeScreenshot(any(), any(), any())
        }
        verify(service, never()).rootInActiveWindow
    }

    private fun root(
        packageName: String? = Platform.DoorDash.packageName,
        child: AccessibilityNodeInfo? = null,
    ): AccessibilityNodeInfo {
        val root = mock<AccessibilityNodeInfo>()
        whenever(root.packageName).thenReturn(packageName)
        if (child != null) {
            whenever(root.childCount).thenReturn(1)
            whenever(root.getChild(0)).thenReturn(child)
        }
        return root
    }

    private fun window(
        root: AccessibilityNodeInfo?,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AccessibilityWindowInfo {
        val window = mock<AccessibilityWindowInfo>()
        whenever(window.root).thenReturn(root)
        whenever(window.type).thenReturn(type)
        whenever(window.displayId).thenReturn(displayId)
        return window
    }
}
