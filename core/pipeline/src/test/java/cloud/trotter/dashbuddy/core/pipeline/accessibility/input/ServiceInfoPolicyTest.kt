package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 — the package subscription is a pure function of the consent: only ALLOWED widens to all
 * packages; UNDECIDED and DECLINED keep exactly the watched registry (fail-closed), and the
 * filtered list never names our own package.
 */
class ServiceInfoPolicyTest {

    private val watched = Platform.watchedPackages

    @Test
    fun `ALLOWED widens to every package`() {
        assertNull(ServiceInfoPolicy.packageNamesFor(EventReceiptConsent.ALLOWED, watched))
        assertTrue(ServiceInfoPolicy.isWide(EventReceiptConsent.ALLOWED))
    }

    @Test
    fun `UNDECIDED and DECLINED keep exactly the watched registry`() {
        for (consent in listOf(EventReceiptConsent.UNDECIDED, EventReceiptConsent.DECLINED)) {
            val names = ServiceInfoPolicy.packageNamesFor(consent, watched)
            assertNotNull("$consent must never widen", names)
            assertEquals(watched, names!!.toSet())
            assertEquals("no duplicates", watched.size, names.size)
            assertFalse(ServiceInfoPolicy.isWide(consent))
        }
    }

    @Test
    fun `the filtered list never names our own package`() {
        EventReceiptConsent.entries.forEach { consent ->
            val names = ServiceInfoPolicy.packageNamesFor(consent, watched) ?: return@forEach
            assertFalse(names.any { it.startsWith("cloud.trotter.dashbuddy") })
        }
    }

    @Test
    fun `every consent value is decided - none widens except ALLOWED`() {
        val wide = EventReceiptConsent.entries.filter {
            ServiceInfoPolicy.packageNamesFor(it, watched) == null
        }
        assertEquals(listOf(EventReceiptConsent.ALLOWED), wide)
    }
}
