package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun `an empty registry is refused for every consent - an empty packageNames means ALL packages`() {
        EventReceiptConsent.entries.forEach { consent ->
            assertThrows(IllegalArgumentException::class.java) {
                ServiceInfoPolicy.packageNamesFor(consent, emptySet())
            }
        }
    }

    @Test
    fun `the production registry is non-empty`() {
        assertTrue(Platform.watchedPackages.isNotEmpty())
    }

    @Test
    fun `the WARN fires only for a widening apply on SDK 30, once`() {
        assertTrue(ServiceInfoPolicy.shouldWarnUnreliable(EventReceiptConsent.ALLOWED, 30, alreadyWarned = false))
        assertFalse(ServiceInfoPolicy.shouldWarnUnreliable(EventReceiptConsent.ALLOWED, 30, alreadyWarned = true))
        assertFalse(ServiceInfoPolicy.shouldWarnUnreliable(EventReceiptConsent.ALLOWED, 31, alreadyWarned = false))
        assertFalse(ServiceInfoPolicy.shouldWarnUnreliable(EventReceiptConsent.DECLINED, 30, alreadyWarned = false))
        assertFalse(ServiceInfoPolicy.shouldWarnUnreliable(EventReceiptConsent.UNDECIDED, 30, alreadyWarned = false))
    }

    @Test
    fun `isWide is a faithful dedup key for the policy output (NN4)`() {
        for (a in EventReceiptConsent.entries) for (b in EventReceiptConsent.entries) {
            val sameOutput = ServiceInfoPolicy.packageNamesFor(a, watched)
                .contentEquals(ServiceInfoPolicy.packageNamesFor(b, watched))
            assertEquals("$a vs $b", sameOutput, ServiceInfoPolicy.isWide(a) == ServiceInfoPolicy.isWide(b))
        }
    }

    @Test
    fun `only a debug build that DECLINED disables its own service (QQ3)`() {
        for (consent in EventReceiptConsent.entries) {
            assertFalse("release never disables ($consent)", ServiceInfoPolicy.shouldDisableSelf(consent, isDebugBuild = false))
            assertEquals(
                "debug $consent",
                consent == EventReceiptConsent.DECLINED,
                ServiceInfoPolicy.shouldDisableSelf(consent, isDebugBuild = true),
            )
        }
    }
}
