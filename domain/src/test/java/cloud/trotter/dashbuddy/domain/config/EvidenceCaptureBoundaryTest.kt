package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.config.EvidenceCaptureBoundary.Verdict
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceCaptureBoundaryTest {
    private val ownPackage = "cloud.trotter.dashbuddy"
    private val platforms = Platform.entries.filter { it != Platform.Unknown }.toSet()

    @Test
    fun `disabled wins regardless of front package`() {
        (platforms.map { it.packageName } + listOf(null, ownPackage, "com.android.chrome")).forEach { front ->
            assertEquals(Verdict.SKIP_DISABLED, decide(front, allowedNow = false))
        }
    }

    @Test
    fun `null front is unreadable even with no enabled platforms`() {
        assertEquals(Verdict.SKIP_UNREADABLE, decide(null))
        assertEquals(Verdict.SKIP_UNREADABLE, decide(null, enabled = emptySet()))
    }

    @Test
    fun `own app captures even with no enabled platforms`() {
        assertEquals(Verdict.CAPTURE, decide(ownPackage, enabled = emptySet()))
    }

    @Test
    fun `each enabled delivery platform captures`() {
        platforms.forEach { platform ->
            assertEquals(Verdict.CAPTURE, decide(platform.packageName, enabled = setOf(platform)))
        }
    }

    @Test
    fun `disabled delivery platform skips`() {
        platforms.forEach { platform ->
            assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(platform.packageName, enabled = platforms - platform))
        }
    }

    @Test
    fun `foreign or empty package skips`() {
        listOf("com.android.chrome", "com.google.android.apps.maps", "").forEach { front ->
            assertEquals(Verdict.SKIP_NOT_DELIVERY_APP, decide(front))
        }
    }

    private fun decide(
        frontPackage: String?,
        allowedNow: Boolean = true,
        enabled: Set<Platform> = platforms,
    ): Verdict = EvidenceCaptureBoundary.decide(allowedNow, frontPackage, ownPackage, enabled)
}
