package cloud.trotter.log.gig.ui.main.setup.permissions

import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * #170 — the screen reading card's facts come from the registry and the build table: it names the platforms the
 * build reads (the bound ones; every one with a package in debug), and only a build that does not declare itself an accessibility tool (Play) lists the offer pop-ups
 * it can't read.
 */
class AccessibilityDisclosureFactsTest {

    @Test
    fun `the card names the bound platforms`() {
        val facts = AccessibilityDisclosureFacts.of(accessibilityTool = false, screenEventsOffered = false, readsUnboundPlatforms = false)
        assertEquals(Platform.boundPlatforms.map { it.displayName }, facts.appNames)
        assertEquals(listOf("DoorDash", "Uber Driver"), facts.appNames)
    }

    /** #170 — the debug build reads every registry platform with a package, so its card names all of them. */
    @Test
    fun `the debug build's card names every platform it reads`() {
        val debug = AccessibilityDisclosureFacts.of(
            accessibilityTool = true, screenEventsOffered = true, readsUnboundPlatforms = true,
        )
        assertEquals(Platform.readable(includeUnbound = true).map { it.displayName }, debug.appNames)
        assertEquals(listOf("DoorDash", "Uber Driver", "Instacart Shopper", "Walmart Spark"), debug.appNames)
        assertEquals(emptyList<String>(), debug.unreadOfferPopUpApps)
    }

    @Test
    fun `only the Play build lists the offer pop-ups it can't read`() {
        val play = AccessibilityDisclosureFacts.of(accessibilityTool = false, screenEventsOffered = false, readsUnboundPlatforms = false)
        assertEquals(Platform.boundPlatforms.filter { it.offerOverlay }.map { it.displayName }, play.unreadOfferPopUpApps)
        assertTrue(play.unreadOfferPopUpApps.isNotEmpty())
        assertFalse(play.screenEventsOffered)

        val sideload = AccessibilityDisclosureFacts.of(accessibilityTool = true, screenEventsOffered = true, readsUnboundPlatforms = false)
        assertEquals(emptyList<String>(), sideload.unreadOfferPopUpApps)
        assertTrue(sideload.screenEventsOffered)
    }

    @Test
    fun `app names join as a plain list`() {
        val pair = "%1\$s and %2\$s"
        assertEquals("", joinAppNames(emptyList(), pair, Locale.ROOT))
        assertEquals("A", joinAppNames(listOf("A"), pair, Locale.ROOT))
        assertEquals("A and B", joinAppNames(listOf("A", "B"), pair, Locale.ROOT))
        assertEquals("A, B and C", joinAppNames(listOf("A", "B", "C"), pair, Locale.ROOT))
        assertEquals("A y B", joinAppNames(listOf("A", "B"), "%1\$s y %2\$s", Locale.forLanguageTag("es")))
    }
}
