package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #432 — the fail-closed text backstop for UNKNOWN captures. */
class SensitiveTextMarkersTest {

    private fun tree(vararg texts: String) = UiNode(
        children = texts.map { UiNode(text = it) },
    )

    @Test
    fun `keyword markers hit case-insensitively`() {
        assertEquals("Routing Number", SensitiveTextMarkers.findMarker(tree("Your routing number is below")))
        assertEquals("Available Balance", SensitiveTextMarkers.findMarker(tree("available balance: \$52.10")))
        assertEquals("Instant Pay", SensitiveTextMarkers.findMarker(tree("Set up instant pay")))
        assertEquals("CVV", SensitiveTextMarkers.findMarker(tree("Enter CVV")))
    }

    @Test
    fun `shaped values hit - SSN and card PAN`() {
        assertNotNull(SensitiveTextMarkers.findMarker(tree("ID 123-45-6789 on file")))
        assertNotNull(SensitiveTextMarkers.findMarker(tree("4111 1111 1111 1111")))
        assertNotNull(SensitiveTextMarkers.findMarker(tree("4111-1111-1111-1111")))
    }

    @Test
    fun `ordinary dash screens stay clean`() {
        assertNull(
            SensitiveTextMarkers.findMarker(
                tree("Pickup from Chipotle", "Deliver by 7:45 PM", "\$7.50", "3.2 mi", "Accept"),
            ),
        )
        // Plain money, dates, and phone-shaped strings must not trip the shapes.
        assertNull(SensitiveTextMarkers.findMarker(tree("Total: \$1,234.56", "ETA 12-45")))
    }

    @Test
    fun `flat text overload works for notification bodies`() {
        assertNotNull(SensitiveTextMarkers.findMarker("Your bank account transfer was initiated"))
        assertNull(SensitiveTextMarkers.findMarker("New order: Chipotle, \$8.25 total"))
    }
}
