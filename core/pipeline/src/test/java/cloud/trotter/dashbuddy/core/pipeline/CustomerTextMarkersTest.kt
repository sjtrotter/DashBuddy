package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #624 marker-SSOT unit tests for [CustomerTextMarkers] — the recognized-frame
 * customer-PII backstop's keyword set and the already-redacted skip rule (VET V1)
 * plus the deliberate "Heading to " exclusion (VET V2).
 */
class CustomerTextMarkersTest {

    @Test
    fun `a customer marker prefix is detected`() {
        assertEquals("Deliver to ", CustomerTextMarkers.unredactedMarker("Deliver to Jane Q Doe"))
        assertEquals("Order for ", CustomerTextMarkers.unredactedMarker("Order for John Smith"))
        assertEquals("Verify items for ", CustomerTextMarkers.unredactedMarker("Verify items for Jane"))
        assertEquals("Delivery for ", CustomerTextMarkers.unredactedMarker("Delivery for John"))
    }

    @Test
    fun `an already-redacted node is skipped (VET V1)`() {
        // The distinctness suffix form and the plain form both contain "[redacted".
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to [redacted:ab12]"))
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to [redacted]"))
        // The rule's OWN dropoff_reminder output must not re-trip the backstop.
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver to door of [redacted:ab12]"))
    }

    @Test
    fun `Heading to is NOT a marker - it prefixes store names (VET V2)`() {
        assertNull(CustomerTextMarkers.unredactedMarker("Heading to Chipotle"))
    }

    @Test
    fun `Deliver by is NOT a marker - it is a time`() {
        assertNull(CustomerTextMarkers.unredactedMarker("Deliver by 5:00 PM"))
    }

    @Test
    fun `null and blank are clean`() {
        assertNull(CustomerTextMarkers.unredactedMarker(null))
        assertNull(CustomerTextMarkers.unredactedMarker(""))
    }

    @Test
    fun `firstUnredactedMarker walks the whole tree and scrub masks only the offending node`() {
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Directions"),
                UiNode(text = "Deliver to Jane Q Doe"),
                UiNode(text = "Got it"),
            ),
        )
        assertEquals("Deliver to ", CustomerTextMarkers.firstUnredactedMarker(tree))

        val scrubbed = CustomerTextMarkers.scrub(tree)
        assertEquals("Directions", scrubbed.children[0].text)
        assertEquals("[redacted]", scrubbed.children[1].text)
        assertEquals("Got it", scrubbed.children[2].text)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarker(scrubbed))
    }
}
