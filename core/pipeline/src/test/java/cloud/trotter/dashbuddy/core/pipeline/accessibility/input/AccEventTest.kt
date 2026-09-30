package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1148 D1 + review F3 — [AccEvent.from] copies scalars at the callback and NEVER performs the
 * `getSource()` binder fetch there: a click carries an owned event copy that the click pipeline
 * resolves on its collector.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccEventTest {

    @Test
    fun `a click envelope never fetches the source node on the callback thread`() {
        @Suppress("DEPRECATION")
        val real = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "com.doordash.driverapp"
            className = "android.widget.Button"
        }
        val event = spy(real)

        val acc = AccEvent.from(event)

        verify(event, never()).source
        assertEquals(AccessibilityEvent.TYPE_VIEW_CLICKED, acc.type)
        assertEquals("com.doordash.driverapp", acc.packageName)
        assertEquals("android.widget.Button", acc.className)
        assertNotNull("a click carries a deferred source handle", acc.source)
        acc.source!!.release() // never throws
    }

    @Test
    fun `a non-click envelope carries no source handle`() {
        @Suppress("DEPRECATION")
        val real = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
            packageName = "com.doordash.driverapp"
            contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        }
        val event = spy(real)

        val acc = AccEvent.from(event)

        verify(event, never()).source
        assertNull(acc.source)
        assertEquals(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT, acc.contentChangeTypes)
    }
}
