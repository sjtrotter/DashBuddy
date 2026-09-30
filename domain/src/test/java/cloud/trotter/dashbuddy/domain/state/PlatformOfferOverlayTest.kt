package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1152 D1: which platforms draw their offer as a system-layer overlay is REGISTRY data
 * ([Platform.offerOverlay]), and [Platform.overlayPackages] derives from it — the sensor layer
 * never names a platform.
 */
class PlatformOfferOverlayTest {

    @Test
    fun `Uber draws an offer overlay`() {
        assertTrue(Platform.Uber.offerOverlay)
    }

    @Test
    fun `DoorDash and every other entry do not`() {
        assertFalse(Platform.DoorDash.offerOverlay)
        assertEquals(listOf(Platform.Uber), Platform.entries.filter { it.offerOverlay })
    }

    @Test
    fun `overlayPackages is derived from the flag`() {
        assertEquals(setOf("com.ubercab.driver"), Platform.overlayPackages)
        assertEquals(
            Platform.entries.filter { it.offerOverlay }.mapNotNull { it.packageName }.toSet(),
            Platform.overlayPackages,
        )
    }
}
