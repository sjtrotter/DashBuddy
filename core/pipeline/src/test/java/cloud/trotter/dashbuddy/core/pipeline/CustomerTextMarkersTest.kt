package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #624/#632 marker-SSOT unit tests for [CustomerTextMarkers] — the recognized-frame
 * customer-PII backstop's keyword set and the already-redacted skip rule (VET V1)
 * plus the deliberate "Heading to " exclusion (VET V2), across BOTH the screen
 * (UiNode tree) and notification (flat field) helpers.
 */
class CustomerTextMarkersTest {

    private fun notif(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
        subText: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        subText = subText, packageName = "pkg", postTime = 0L, isClearable = true,
    )

    @Test
    fun `a customer marker prefix is detected`() {
        assertEquals("Deliver to ", CustomerTextMarkers.unredactedMarker("Deliver to Jane Q Doe"))
        assertEquals("Order for ", CustomerTextMarkers.unredactedMarker("Order for John Smith"))
        assertEquals("Verify items for ", CustomerTextMarkers.unredactedMarker("Verify items for Jane"))
        assertEquals("Delivery for ", CustomerTextMarkers.unredactedMarker("Delivery for John"))
    }

    @Test
    fun `the Pickup for task-detail marker is detected (#806)`() {
        // The DoorDash pickup / "Current task" bottom-sheet lead-in that leaked the
        // customer name to UNKNOWN captures; redacted on recognized captures already.
        assertEquals("Pickup for ", CustomerTextMarkers.unredactedMarker("Pickup for Jane D."))
        // Already-redacted form (VET V1) is skipped so a rule's own output can't re-trip.
        assertNull(CustomerTextMarkers.unredactedMarker("Pickup for [redacted:ab12]"))
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

    // --- Notification path (#632) --------------------------------------------

    @Test
    fun `cross-platform notif customer lead-ins are detected (#632)`() {
        // Uber notification vocabulary.
        assertEquals(
            "Leave the order at ",
            CustomerTextMarkers.unredactedMarker("Leave the order at 123 Main St, Apt 4"),
        )
        assertEquals(
            "Meet at door for ",
            CustomerTextMarkers.unredactedMarker("Meet at door for Jane Q Doe"),
        )
        // DoorDash chat push title.
        assertEquals("Message from ", CustomerTextMarkers.unredactedMarker("Message from Jennifer"))
    }

    @Test
    fun `firstUnredactedMarkerInNotif scans flat fields and scrubNotif masks the whole field`() {
        val raw = notif(
            title = "Leave the order at 123 Main St, Apt 4",
            text = "Your delivery from H-E-B", // store-only, kept
        )
        assertEquals("Leave the order at ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))

        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        // Whole-field scrub (flat strings, not a tree).
        assertEquals("[redacted]", scrubbed.title)
        // Store-only field is untouched (merchants are not PII).
        assertEquals("Your delivery from H-E-B", scrubbed.text)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(scrubbed))
    }

    @Test
    fun `an already-redacted notif field is skipped (VET V1)`() {
        val raw = notif(title = "Leave the order at [redacted:ab12]")
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
    }

    @Test
    fun `store-only notif text is NOT a customer marker`() {
        // "Your delivery from <store>" prefixes a MERCHANT, not customer data.
        assertNull(CustomerTextMarkers.unredactedMarker("Your delivery from H-E-B"))
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(notif(text = "Your delivery from H-E-B")))
    }

    // --- actionLabels (#666 item 2) -------------------------------------------

    @Test
    fun `a customer-marker action label is detected and scrubbed, a clean label is untouched`() {
        val raw = notif(title = "New order").copy(
            actionLabels = listOf("Message from Jane", "Dismiss"),
        )
        assertEquals("Message from ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))

        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        assertEquals("[redacted]", scrubbed.actionLabels[0])
        assertEquals("Dismiss", scrubbed.actionLabels[1])
        // Text fields untouched (marker was only in the action label).
        assertEquals("New order", scrubbed.title)
        // Clean after scrub.
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(scrubbed))
    }

    @Test
    fun `action labels with no marker are left untouched`() {
        val raw = notif(title = "New order").copy(actionLabels = listOf("Accept", "Decline"))
        assertNull(CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
        val scrubbed = CustomerTextMarkers.scrubNotif(raw)
        assertEquals(listOf("Accept", "Decline"), scrubbed.actionLabels)
    }

    @Test
    fun `firstUnredactedMarkerInNotif checks text fields before action labels`() {
        val raw = notif(title = "Deliver to Jane").copy(actionLabels = listOf("Message from Bob"))
        // Text-field marker found first (order doesn't affect correctness, but pins behavior).
        assertEquals("Deliver to ", CustomerTextMarkers.firstUnredactedMarkerInNotif(raw))
    }

    @Test
    fun `no-lead-in customer shapes are the documented residual the rule redact owns`() {
        // Uber trip_en_route_dropoff title is a WHOLE address with NO lead-in marker —
        // a prefix scan cannot catch it; the rule-declared `redact` is the control.
        assertNull(CustomerTextMarkers.unredactedMarker("123 Main Street, Austin"))
        // DoorDash order_ready puts the customer name at the START (no lead-in).
        assertNull(CustomerTextMarkers.unredactedMarker("Adam's order is ready for pickup at 7-Eleven"))
    }
}
