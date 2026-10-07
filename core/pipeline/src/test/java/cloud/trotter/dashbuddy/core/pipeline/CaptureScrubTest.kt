package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * #432 — UNKNOWN captures are scrubbed by the fail-closed text backstop:
 * an unrecognized sensitive screen must never reach the CaptureBus, while
 * benign UNKNOWNs still capture for triage.
 */
class CaptureScrubTest {

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }
    private val stats = PipelineStats()
    private val writer = CaptureWriter(captureBus, stats, NoRedaction)

    private fun screenEvent(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(
            tree = tree,
            packageName = "com.doordash.driverapp",
        ),
        packageName = "com.doordash.driverapp",
    )

    private fun unknownObs() = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    @Test
    fun `UNKNOWN screen with sensitive text is never offered to the capture bus`() {
        val toxic = UiNode(children = listOf(UiNode(text = "Available balance: \$152.10")))
        writer.captureScreen(unknownObs(), screenEvent(toxic))

        verify(captureBus, never()).offer(any(), any(), any(), any(), any(), any())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `benign UNKNOWN screen still captures for triage`() {
        val benign = UiNode(children = listOf(UiNode(text = "Some new promo screen")))
        writer.captureScreen(unknownObs(), screenEvent(benign))

        verify(captureBus, times(1)).offer(any(), any(), any(), any(), any(), any())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `recognized screens are not scrubbed - the rule gate already vetted them`() {
        val tree = UiNode(children = listOf(UiNode(text = "ending in 1234")))
        val recognized = unknownObs().copy(ruleId = "doordash.screen.test", target = "idle_map")
        writer.captureScreen(recognized, screenEvent(tree))

        verify(captureBus, times(1)).offer(any(), any(), any(), any(), any(), any())
        assertTrue(stats.scrubbedUnknownCaptureCount == 0L)
    }

    // #597: clicks now always persist (no bus dedup) — so the click path needs
    // the same fail-closed backstop: an UNKNOWN click on an unruled sensitive
    // surface must never persist the tapped node's text.

    private fun unknownClickObs() = Observation.Click(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    @Test
    fun `UNKNOWN click with sensitive text is never offered to the capture bus`() {
        val toxic = UiNode(text = "Available balance: \$152.10", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = toxic, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `benign UNKNOWN click still captures for triage`() {
        val benign = UiNode(text = "Some new button", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = benign, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    // #884: the fielded leak class. A DasherDirect transfer BUTTON is serialized in
    // ISOLATION on the click path — the tapped node alone, with none of the window's
    // co-present "$X.XX available" balance line — so the AND-pair sensitive RULE that
    // blocks the window ("Transfer out"/"Transfer in" + "available") cannot fire here.
    // The marker backstop is the only control on this envelope, and before #884 neither
    // the amount-bearing label nor the "in" direction matched any marker: `Transfer
    // $45.66` / `$83.65` / `$68.52` and `Transfer in` all reached disk on build ddd9e7ff.

    private fun clickNode(text: String) = UiNode(text = text, isClickable = true)

    private fun captureUnknownClick(node: UiNode) = writer.captureClick(
        unknownClickObs(),
        PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
        screenTarget = "side_nav_drawer",
        screenRuleId = null,
    )

    @Test
    fun `UNKNOWN click on an amount-bearing transfer button is never offered to the capture bus`() {
        captureUnknownClick(clickNode("Transfer \$12.34"))

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `UNKNOWN click on the Transfer in button is never offered to the capture bus`() {
        captureUnknownClick(clickNode("Transfer in"))

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `UNKNOWN click on the sibling Transfer out button is still dropped (#794 regression)`() {
        captureUnknownClick(clickNode("Transfer out"))

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `an ordinary money-bearing click label still captures - the shape needs the transfer word`() {
        captureUnknownClick(clickNode("Add \$12.34 tip"))

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    // #666 item 2c: the UNKNOWN-notification backstop must scan actionLabels too —
    // previously only the flat text fields (title/text/bigText/tickerText/subText)
    // were scanned, so a sensitive marker living ONLY in a push action button label
    // (e.g. a future "Cash out" quick-action) would have shipped uncaught.

    private fun unknownNotifObs() = Observation.Notification(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    private fun rawNotif(title: String? = null, actionLabels: List<String> = emptyList()) =
        RawNotificationData(
            title = title, text = null, tickerText = null, bigText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
            actionLabels = actionLabels,
        )

    @Test
    fun `UNKNOWN notification with a sensitive marker ONLY in an action label is scrubbed`() {
        val toxic = rawNotif(title = "New promo", actionLabels = listOf("Cash out"))
        writer.captureNotification(unknownNotifObs(), toxic)

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `benign UNKNOWN notification with clean action labels still captures for triage`() {
        val benign = rawNotif(title = "New promo", actionLabels = listOf("Accept", "Decline"))
        writer.captureNotification(unknownNotifObs(), benign)

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    // #806: the UNKNOWN customer-PII scrub now covers the notification and click
    // paths too (not just screens) — an unrecognized customer-bearing push / a tap
    // on an unrecognized "Deliver to <name>" row must not persist raw customer text.

    /** The envelope JSON offered to the bus (5th positional arg of offer). */
    private fun offeredEnvelope(): String {
        val cap = argumentCaptor<String>()
        verify(captureBus).offer(any(), any(), anyOrNull(), any(), cap.capture(), anyOrNull())
        return cap.lastValue
    }

    @Test
    fun `UNKNOWN notification with a customer marker in the title is scrubbed`() {
        writer.captureNotification(unknownNotifObs(), rawNotif(title = "Message from Jane"))
        val json = offeredEnvelope()
        assertFalse("leaked customer name scrubbed", json.contains("Jane"))
        assertTrue("field became [redacted]", json.contains("[redacted]"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
        // The sensitive-DROP counter is untouched — this is a customer scrub, not a drop.
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
        assertEquals(0L, stats.notifRedactBackstopScrubCount)
    }

    @Test
    fun `UNKNOWN notification with a customer marker in the body is scrubbed`() {
        val raw = rawNotif(title = "New order").copy(text = "Meet at door for Jane Q Doe")
        writer.captureNotification(unknownNotifObs(), raw)
        val json = offeredEnvelope()
        assertFalse("leaked customer name scrubbed", json.contains("Jane Q Doe"))
        assertTrue("field became [redacted]", json.contains("[redacted]"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN notification with a customer marker ONLY in an action label is scrubbed`() {
        val raw = rawNotif(title = "New order", actionLabels = listOf("Message from Bob", "Dismiss"))
        writer.captureNotification(unknownNotifObs(), raw)
        val json = offeredEnvelope()
        assertFalse("leaked customer name scrubbed", json.contains("Bob"))
        assertTrue("action label became [redacted]", json.contains("[redacted]"))
        assertTrue("clean action label intact", json.contains("Dismiss"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN click node with a customer marker is scrubbed`() {
        val node = UiNode(text = "Deliver to Jane D.", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        val json = offeredEnvelope()
        assertFalse("leaked customer name scrubbed", json.contains("Jane D."))
        assertTrue("node became [redacted]", json.contains("[redacted]"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    // #910: the UNKNOWN envelopes carry a SECOND, structural scan keyed on the node's
    // own view id — the split-node shape the text scan is blind to by construction.

    /**
     * The fielded UNKNOWN window: a "Delivery for" label + a BARE name sibling.
     *
     * Ground truth from two independent pulls — `…/2026/07/29/captures/doordash/
     * accessibility.window/UNKNOWN/2026-07-28_16-40-44-160__…__6fa230.json` and, on the
     * next dash, `…/2026/07/30/captures/…/UNKNOWN/2026-07-29_18-14-06-648__…__6fa230.json`
     * (same window signature, once per job). Node IDS are ground truth; every VALUE below
     * is invented.
     */
    private fun deliveryForCard() = UiNode(
        children = listOf(
            UiNode(
                viewIdResourceName = "com.doordash.driverapp:id/user_name_label",
                // Exactly "Delivery for" — no trailing space, no name. The
                // "Delivery for " marker cannot match it.
                text = "Delivery for",
            ),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Testname Q"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/address_line_1", text = "1234 Testfixture Dr"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/address_line_2", text = "Austin, TX 78701"),
            UiNode(text = "Directions"),
        ),
    )

    @Test
    fun `UNKNOWN screen with a bare customer_name-user_name node is scrubbed by id (#910)`() {
        writer.captureScreen(unknownObs(), screenEvent(deliveryForCard()))

        val json = offeredEnvelope()
        assertFalse("bare customer name scrubbed", json.contains("Testname Q"))
        assertFalse("street line scrubbed", json.contains("1234 Testfixture Dr"))
        assertFalse("city/ST/ZIP scrubbed", json.contains("78701"))
        assertTrue("nodes became [redacted]", json.contains("[redacted]"))
        // The label is app chrome — kept, so the frame stays triage-useful.
        assertTrue("the 'Delivery for' label survives", json.contains("Delivery for"))
        assertTrue("unrelated chrome survives", json.contains("Directions"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `UNKNOWN click node carrying a bare customer_name is scrubbed by id (#910)`() {
        val node = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/pickup_row",
            isClickable = true,
            children = listOf(
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Testname Q"),
                UiNode(viewIdResourceName = "com.doordash.driverapp:id/merchant_name", text = "Pei Wei"),
            ),
        )
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = "pickup_post_arrival_multi",
            // No screen redact available here (the #910 B path is tested separately) —
            // this is the id backstop standing alone.
            screenRuleId = null,
        )

        val json = offeredEnvelope()
        assertFalse("bare customer name scrubbed", json.contains("Testname Q"))
        assertTrue("merchant kept — merchants are not PII", json.contains("Pei Wei"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `the id backstop is UNKNOWN-only - a recognized frame keeps its rule's decisions (#910)`() {
        // On a recognized frame the rule's declared `redact` is the primary control and
        // its decisions are deliberate (#886 keeps pickup_navigation's MERCHANT address
        // raw). An id scan there could fight the ruleset, so scope stays UNKNOWN-only.
        val recognized = unknownObs().copy(
            ruleId = "doordash.screen.test",
            target = "pickup_navigation",
        )
        writer.captureScreen(recognized, screenEvent(deliveryForCard()))

        assertTrue("recognized frame is not id-scrubbed", offeredEnvelope().contains("Testname Q"))
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `a benign UNKNOWN click node with no customer marker persists unchanged`() {
        val node = UiNode(text = "Some new button", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        assertTrue(offeredEnvelope().contains("Some new button"))
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN screen with a text input is captured with the input masked and counted (#919)`() {
        val draft = "they only had one of the juice boxes in stock"
        val tree = UiNode(children = listOf(
            UiNode(className = "android.widget.TextView", text = "Chat"),
            UiNode(className = "android.widget.EditText", text = draft),
        ))
        writer.captureScreen(unknownObs(), screenEvent(tree))

        // The argumentCaptor helper verifies exactly one offer: the frame is captured, not dropped.
        val json = offeredEnvelope()
        assertFalse("draft scrubbed", json.contains(draft))
        assertTrue("input became [redacted]", json.contains("[redacted]"))
        assertTrue("chrome survives", json.contains("Chat"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    // #1116: the UNKNOWN-only address-block backstop — an id-less, prefix-less street → City, ST ZIP
    // pair plus the block's quoted note and short code, which none of the three scans above can see.

    private fun addressSheet() = UiNode(children = listOf(
        UiNode(contentDescription = "Close sheet", isClickable = true),
        UiNode(children = listOf(
            UiNode(children = listOf(UiNode(text = "1234 Sample Ridge Dr"), UiNode(text = "San Antonio, TX 78200"))),
            UiNode(children = listOf(UiNode(text = "Leave it at my door"), UiNode(text = "\"Leave it by the sample gnome\""))),
            UiNode(children = listOf(UiNode(text = "000"))),
        )),
    ))

    @Test
    fun `UNKNOWN screen with an id-less address block is captured with the block masked and counted (#1116)`() {
        writer.captureScreen(unknownObs(), screenEvent(addressSheet()))

        val json = offeredEnvelope()
        assertFalse("street scrubbed", json.contains("Sample Ridge"))
        assertFalse("ZIP scrubbed", json.contains("78200"))
        assertFalse("note scrubbed", json.contains("sample gnome"))
        assertFalse("code scrubbed", Regex(""""text"\s*:\s*"000"""").containsMatchIn(json))
        assertTrue("chrome survives", json.contains("Close sheet") && json.contains("Leave it at my door"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `the address-block backstop is UNKNOWN-only - a recognized frame keeps its rule's decisions (#1116)`() {
        val recognized = unknownObs().copy(ruleId = "doordash.screen.test", target = "pickup_navigation")
        writer.captureScreen(recognized, screenEvent(addressSheet()))

        assertTrue("recognized frame is not address-scrubbed", offeredEnvelope().contains("Sample Ridge"))
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN click subtree carrying an address block is masked (#1116)`() {
        val node = UiNode(
            isClickable = true,
            children = listOf(UiNode(text = "Stop 1"), UiNode(text = "55 Other Lane"), UiNode(text = "Austin, TX 78701")),
        )
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        val json = offeredEnvelope()
        assertFalse("street scrubbed", json.contains("Other Lane"))
        assertFalse("ZIP scrubbed", json.contains("78701"))
        assertTrue("chrome survives", json.contains("Stop 1"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `a disabled capture bus never builds an envelope for an address block (#1116 release posture)`() {
        val disabled: CaptureBus = mock { on { isEnabled } doReturn false }
        CaptureWriter(disabled, stats, NoRedaction).captureScreen(unknownObs(), screenEvent(addressSheet()))

        verify(disabled, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    // #1116 follow-up: the gated `Return <name> to <store>` task line on UNKNOWN envelopes — the NAME is
    // masked, the store is kept (#886), and the platform's `Return to dash` chrome never matches.

    @Test
    fun `UNKNOWN screen masks only the name of a return task line (#1116)`() {
        val tree = UiNode(children = listOf(UiNode(text = "Return Avery K to Sample Store"), UiNode(text = "Close sheet")))
        writer.captureScreen(unknownObs(), screenEvent(tree))

        val json = offeredEnvelope()
        assertFalse("name scrubbed", json.contains("Avery K"))
        assertTrue("store kept, lead-in kept", json.contains("Return [redacted] to Sample Store"))
        assertEquals(1L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN screen with Return to dash chrome is untouched (#1116)`() {
        val tree = UiNode(children = listOf(UiNode(text = "Return to dash"), UiNode(text = "Return to dash to keep earning")))
        writer.captureScreen(unknownObs(), screenEvent(tree))

        val json = offeredEnvelope()
        assertTrue(json.contains("\"Return to dash\""))
        assertTrue(json.contains("Return to dash to keep earning"))
        assertEquals(0L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `UNKNOWN click on a return task row masks only the name (#1116)`() {
        val node = UiNode(isClickable = true, contentDescription = "Return Jordan T to Sample Market")
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        val json = offeredEnvelope()
        assertFalse(json.contains("Jordan T"))
        assertTrue(json.contains("Return [redacted] to Sample Market"))
    }

    @Test
    fun `the return-line gate is UNKNOWN-only (#1116)`() {
        val recognized = unknownObs().copy(ruleId = "doordash.screen.test", target = "timeline")
        writer.captureScreen(recognized, screenEvent(UiNode(children = listOf(UiNode(text = "Return Avery K to Sample Store")))))
        assertTrue("a recognized frame keeps its rule's decisions", offeredEnvelope().contains("Avery K"))
    }

    @Test
    fun `return-line gate shapes (#1116)`() {
        val masked = mapOf(
            "Return Avery K to Sample Store" to "Return [redacted] to Sample Store",
            "Return\tAvery K to Sample Store" to "Return\t[redacted] to Sample Store",
            "return Avery Kim  to  Sample Store #12" to "return [redacted]  to  Sample Store #12",
            "Return Avery K to [redacted:ab12]" to "Return [redacted] to [redacted:ab12]",
        )
        for ((raw, out) in masked) assertEquals(out, CustomerTextMarkers.maskReturnName(raw))
        for (kept in listOf(
            "Return to dash", "Return to dash to keep earning", "Return [redacted] to Sample Store",
            "Return [redacted:ab12] to Sample Store", "Returned items", "Return", "Return Avery K", "Return Avery K to ",
            // PR #1277 review r1 #4: the separator is consumed whole before the chrome check.
            "Return   to dash", "Return  to dash to keep earning", "Return\tto dash",
        )) {
            assertEquals(kept, CustomerTextMarkers.maskReturnName(kept))
        }
    }

    @Test
    fun `a merged field with two return task lines masks both names (r1 #5)`() {
        assertEquals(
            "Return [redacted] to Sample Store\nReturn [redacted] to Sample Market",
            CustomerTextMarkers.maskReturnName("Return Avery K to Sample Store\nReturn Jordan T to Sample Market"),
        )
        assertEquals(
            "Pickup at Sample Store\nReturn [redacted] to Sample Market",
            CustomerTextMarkers.maskReturnName("Pickup at Sample Store\nReturn Jordan T to Sample Market"),
        )
    }

    @Test
    fun `the return-line parse is linear on a maximum-length field (r1 #3)`() {
        for (input in listOf(
            "Return" + " ".repeat(4_090),
            "Return " + "a to ".repeat(817),
            "Return A" + " to".repeat(1_362),
        )) {
            val steps = LongArray(1)
            ReturnTaskLine.mask(input, steps)
            ReturnTaskLine.hasRawName(input, steps)
            assertTrue("${steps[0]} steps for ${input.length} chars", steps[0] <= 8L * input.length)
        }
    }

    @Test
    fun `UNKNOWN click on a node carrying both address lines is masked (r1 #1)`() {
        val node = UiNode(isClickable = true, text = "1234 Sample Ridge Dr\nSan Antonio, TX 78200-1234")
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = node, packageName = "com.doordash.driverapp"),
            screenTarget = null,
            screenRuleId = null,
        )
        val json = offeredEnvelope()
        assertFalse(json.contains("Sample Ridge"))
        assertFalse(json.contains("78200"))
    }
}
