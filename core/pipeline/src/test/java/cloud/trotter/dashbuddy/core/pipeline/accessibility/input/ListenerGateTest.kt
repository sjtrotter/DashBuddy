package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1148 D2 — topology events pass the listener regardless of their package (scope is enforced on
 * the FETCHED roots downstream); every other handled type keeps the enabled-package gate.
 */
@RunWith(RobolectricTestRunner::class)
class ListenerGateTest {

    private val enabled = setOf("com.doordash.driverapp")
    private val handled = AccessibilityListener.HANDLED_TYPES

    private fun admit(type: Int, pkg: String?, isDebug: Boolean = false) =
        ListenerGate.admit(type, pkg, enabled, isDebug, handled)

    @Test
    fun `WINDOWS_CHANGED with a null package is admitted`() {
        assertTrue(admit(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null))
        assertTrue(admit(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, isDebug = true))
    }

    @Test
    fun `WINDOWS_CHANGED with a foreign package is admitted`() {
        assertTrue(admit(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.android.systemui"))
        assertTrue(admit(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "cloud.trotter.dashbuddy"))
    }

    @Test
    fun `CONTENT_CHANGED with a foreign or null package is rejected`() {
        assertFalse(admit(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.android.launcher3"))
        assertFalse(admit(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null))
        assertFalse(admit(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "cloud.trotter.dashbuddy"))
        assertFalse(admit(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.android.launcher3", isDebug = true))
    }

    @Test
    fun `handled types from an enabled package are admitted`() {
        for (type in handled) assertTrue("type 0x%x".format(type), admit(type, "com.doordash.driverapp"))
    }

    @Test
    fun `an unhandled type is never admitted, even from an enabled package`() {
        assertFalse(admit(AccessibilityEvent.TYPE_VIEW_FOCUSED, "com.doordash.driverapp"))
        assertFalse(admit(AccessibilityEvent.TYPE_VIEW_SCROLLED, null))
    }
}
