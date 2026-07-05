package cloud.trotter.dashbuddy.core.pipeline.notification

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.core.pipeline.notification.mapper.toDomain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

/**
 * #590 🔴 Invariant 3 — notification mapping is TOTAL.
 *
 * `StatusBarNotification.toDomain()` reads `notification.extras` and unwraps
 * `getCharSequence(...)`. Notification extras are attacker-influenced untrusted
 * input that unparcels lazily on first read; a malformed/custom-Parcelable extra
 * can throw (`BadParcelableException`) — the textbook listener-crash class. Such
 * a throw propagates up the notification pipeline flow and kills the listener
 * (the crash class #430's supervision closed on the accessibility side).
 *
 * Red-first observation: pre-fix, `toDomain()` returned a non-null
 * `RawNotificationData` and had no catch, so a throwing `getCharSequence`
 * propagated straight out of the mapper (verified by this test throwing before
 * the catch was added). Fix: the mapping is wrapped to return null on any
 * exception (the pipeline's `mapNotNull` drops it) + one PII-free WARN — so one
 * bad notification is dropped and the flow keeps emitting.
 */
class NotificationMapperTotalityTest {

    /** A [StatusBarNotification] whose `notification` and `packageName` are stubbed. */
    private fun sbn(notification: Notification, pkg: String = "com.doordash.driverapp"): StatusBarNotification =
        mock {
            on { this.notification } doReturn notification
            on { packageName } doReturn pkg
        }

    /** A [Notification] whose `extras` bundle throws on any `getCharSequence`. */
    private fun throwingNotification(): Notification {
        val bundle: Bundle = mock {
            on { getCharSequence(any()) } doThrow RuntimeException("simulated BadParcelableException")
        }
        return mock<Notification>().apply { extras = bundle }
    }

    /** A well-formed [Notification] carrying a title. */
    private fun goodNotification(title: String): Notification {
        val bundle: Bundle = mock {
            on { getCharSequence("android.title") } doReturn title
        }
        return mock<Notification>().apply { extras = bundle }
    }

    @Test
    fun `a throwing-extras notification is dropped, not propagated`() {
        assertNull(sbn(throwingNotification()).toDomain())
    }

    @Test
    fun `a well-formed notification still maps`() {
        val mapped = sbn(goodNotification("Order ready")).toDomain()
        assertNotNull(mapped)
        assertEquals("Order ready", mapped!!.title)
    }

    @Test
    fun `after a bad notification the flow keeps emitting`() = runTest {
        val bad = sbn(throwingNotification())
        val good = sbn(goodNotification("Order ready"))
        val out = flowOf(bad, good)
            .mapNotNull { it.toDomain() } // the exact pipeline operator
            .toList()
        assertEquals(1, out.size)
        assertEquals("Order ready", out.single().title)
    }

    @Test
    fun `property - a throw on any single extras key never escapes`() = runTest {
        checkAll(50, Arb.element("android.title", "android.text", "android.bigText", "android.subText")) { key ->
            val bundle: Bundle = mock {
                on { getCharSequence(key) } doThrow RuntimeException("boom on $key")
            }
            val notif = mock<Notification>().apply { extras = bundle }
            // Must not throw; a throwing key means the whole notification is dropped.
            assertNull(sbn(notif).toDomain())
        }
    }
}
