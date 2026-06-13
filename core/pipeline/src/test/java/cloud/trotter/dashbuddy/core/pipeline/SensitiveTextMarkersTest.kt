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
        // DasherDirect Savings flow (#463) — the markers that leaked on 2026-06-12.
        assertEquals("Savings jar", SensitiveTextMarkers.findMarker(tree("Your transfer should now appear in your Savings jar")))
        assertEquals("You transferred", SensitiveTextMarkers.findMarker(tree("You transferred \$9.06")))
        // Alcohol DOCUMENT-capture surfaces only — license scanner + signature pad
        // (#463). The ID-check instruction + the arrival card are NOT markers: we
        // recognize those (customers are hashed, not blocked).
        assertEquals("Scan barcode on the back", SensitiveTextMarkers.findMarker(tree("Scan barcode on the back of license")))
        assertEquals("Driver's License", SensitiveTextMarkers.findMarker(tree("Driver's License")))
        assertEquals("provide their signature", SensitiveTextMarkers.findMarker(tree("Hand your phone to the customer so they can provide their signature.")))
        assertEquals("A recipient signature is required", SensitiveTextMarkers.findMarker(tree("A recipient signature is required for this order")))
    }

    @Test
    fun `alcohol ID-check + arrival text are NOT scrubbed — they're recognized, not blocked (#463 reversal)`() {
        // The ID-check instruction ("matches the recipient") and the arrival
        // banner ("collect a signature at dropoff") carry no document image; we
        // recognize them and hash the customer PII, so they must stay clean.
        assertNull(SensitiveTextMarkers.findMarker(tree("Identity verification", "Verify that the ID matches the recipient and they aren't intoxicated.")))
        assertNull(SensitiveTextMarkers.findMarker(tree("Verify the recipient's identity and collect a signature at dropoff")))
    }

    @Test
    fun `alcohol delivery instruction checklist stays clean — recognize-vs-block boundary (#463)`() {
        // The step-CHECKLIST text (no actual ID/signature capture) must NOT be
        // scrubbed — it's a recognizable flow step (#462), not a sensitive surface.
        assertNull(
            SensitiveTextMarkers.findMarker(
                tree(
                    "Follow all of the steps below to complete this delivery.",
                    "Verify recipient's identity",
                    "Complete delivery",
                ),
            ),
        )
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
