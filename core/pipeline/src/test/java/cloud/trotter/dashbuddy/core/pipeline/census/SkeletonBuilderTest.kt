package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.census.contract.CensusFingerprint
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0011 §2 — every withholding filter step with a positive and a negative case, the required
 * end-to-end vectors, and the whole-frame refusals (sensitive tree, sensitive title, oversize).
 */
class SkeletonBuilderTest {

    private val meta = ReplayMetadata(
        engineVersion = 7,
        rulesetFormatVersion = 2,
        rulesetReleaseTag = "rules-2026.09.30",
        appVersion = "0.9.0+abc1234",
        deviceFingerprint = "google/panther/panther:16/should/never/leave",
        platformAppVersion = "8.97.8",
    )

    private fun slot(value: String?, id: String? = null) = SkeletonBuilder.slotFor(value, id)
    private fun words(n: Int, value: String) = TextSlot(h = CensusHash.of(value), kind = "words:$n")

    // ---- The required vectors (ADR §1/§2, spec) -------------------------------------------------

    @Test
    fun `required vectors`() {
        assertEquals(TextSlot.WITHHELD, slot("Apt 12"))
        assertEquals(TextSlot.WITHHELD, slot("Apt [redacted]"))
        assertEquals(TextSlot.WITHHELD, slot("For [redacted:eacf]"))
        assertEquals(TextSlot.WITHHELD, slot("Jane S is waiting at the door"))
        assertEquals(words(4, "Hand it to me"), slot("Hand it to me"))
        assertEquals(words(2, "Pickup & delivery"), slot("Pickup & delivery"))
        val fortyOne = "Aaaa bbbb cccc dddd eeee ffff gggg hhhhhh" // 41 chars, words:8 by shape
        assertEquals(41, fortyOne.length)
        assertEquals(TextSlot.WITHHELD, slot(fortyOne))
    }

    @Test
    fun `digits mixed and words over 8 are emitted without a hash`() {
        assertEquals(TextSlot(kind = "digits"), slot("4821"))
        assertEquals(TextSlot(kind = "mixed"), slot("\$45.66"))
        assertEquals(TextSlot(kind = "mixed"), slot("7:45 PM"))
        assertEquals(TextSlot(kind = "words:8+"), slot("tap here to see all of the new offers"))
    }

    @Test
    fun `a null or blank field is omitted, even on a PII id`() {
        assertNull(slot(null))
        assertNull(slot("   "))
        assertNull(slot("", "com.doordash.driverapp:id/customer_name"))
    }

    @Test
    fun `the hashed bytes are the trimmed value`() {
        assertEquals(words(1, "Accept"), slot("  Accept  "))
    }

    // ---- Step 1: PII id (ID_MARKERS suffix ∪ PII_ID_SUFFIXES exact) --------------------------------

    @Test
    fun `step 1 - an ID_MARKERS suffix withholds, case-insensitively, on the full id`() {
        assertEquals(FilterStep.PII_ID, SkeletonBuilder.withholdingStep("Accept", "com.doordash.driverapp:id/customer_name"))
        assertEquals(FilterStep.PII_ID, SkeletonBuilder.withholdingStep("Accept", "com.x:id/ORDER_CUSTOMER_NAME"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/description_text_view"))
    }

    @Test
    fun `step 1 - a PII_ID_SUFFIXES exact suffix withholds`() {
        // `order_cx_name` and `tvTitle` are PII_ID_SUFFIXES only (not ID_MARKERS) — the union is used.
        assertEquals(FilterStep.PII_ID, SkeletonBuilder.withholdingStep("Accept", "com.x:id/order_cx_name"))
        assertEquals(FilterStep.PII_ID, SkeletonBuilder.withholdingStep("Accept", "com.x:id/tvTitle"))
    }

    @Test
    fun `step 1 - negative - a chrome id hashes`() {
        assertNull(SkeletonBuilder.withholdingStep("Accept", "com.x:id/accept_button"))
        // PII_ID_SUFFIXES is EXACT after the last slash, so a longer id ending differently is chrome.
        assertNull(SkeletonBuilder.withholdingStep("Accept", "com.x:id/tvTitleLabel"))
        assertEquals(words(1, "Accept"), slot("Accept", "com.x:id/accept_button"))
    }

    // ---- Step 2: length cap ------------------------------------------------------------------------

    @Test
    fun `step 2 - over 40 characters withholds, 40 does not`() {
        val forty = "Aaaa bbbb cccc dddd eeee ffff gggg hhhhh"
        assertEquals(40, forty.length)
        assertNull(SkeletonBuilder.withholdingStep(forty, null))
        assertEquals(FilterStep.LENGTH_CAP, SkeletonBuilder.withholdingStep(forty + "h", null))
    }

    // ---- Step 3: CustomerTextMarkers ---------------------------------------------------------------

    @Test
    fun `step 3 - a customer marker withholds`() {
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Delivery to Morgan", null))
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Meet at door for Sam", null))
        assertNull(SkeletonBuilder.withholdingStep("Delivery details", null))
    }

    // ---- Step 4: customerLeadIn ------------------------------------------------------------------

    @Test
    fun `step 4 - a lead-in with a tail withholds, incl the gated Return prefix`() {
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Pickup from Chipotle", null))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Heading to Walmart", null))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Return Riley P to H-E-B", null))
        // The gate's negative: DoorDash's own button is chrome.
        assertNull(SkeletonBuilder.withholdingStep("Return to dash", null))
    }

    // ---- Step 5: masks -----------------------------------------------------------------------------

    @Test
    fun `step 5 - any mask anywhere withholds`() {
        listOf("[address]", "Call [phone]", "[redacted]", "[redacted:ab12]", "\"[note]\"", "Hi [name]")
            .forEach { assertEquals(it, FilterStep.MASK, SkeletonBuilder.withholdingStep(it, null)) }
        // Step order: a marker-led masked value is caught at step 3 before step 5 sees it.
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Verify items for [name]", null))
        // `[icon]` is DoorDash chrome, not a mask.
        assertNull(SkeletonBuilder.withholdingStep("[icon] Gold", null))
    }

    // ---- Step 6: only words:N hash (non-withholding; steps 7–8 still run) ----------------------------

    @Test
    fun `step 6 - a mixed token continues to step 8 and is withheld there`() {
        assertEquals(FilterStep.PII_SHAPE, SkeletonBuilder.withholdingStep("Apt 12", null))
        assertEquals(TextSlot(kind = "mixed"), slot("Order #1234"))
    }

    // ---- Step 7: the embedded name shape -----------------------------------------------------------

    @Test
    fun `step 7 - an embedded first-name last-initial withholds`() {
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Jane S is waiting at the door", null))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Brandon C", null))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Call jane s. now", null))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("José  R", null))
        assertNull(SkeletonBuilder.withholdingStep("Hand it to me", null))
        assertNull(SkeletonBuilder.withholdingStep("Confirm pickup", null))
    }

    // ---- Step 8: the other PiiShapes ---------------------------------------------------------------

    @Test
    fun `step 8 - each promoted value shape withholds in its own match mode`() {
        mapOf(
            "123 Main St" to "STREET",
            "San Antonio, TX 78254" to "CITY_STATE_ZIP",
            "7610 Fletchers" to "BARE_STREET",
            "Unit 4B" to "APT",
            "pin 4821" to "PIN",
            "\"leave at door\"" to "QUOTED_NOTE",
            "210-555-0100" to "PHONE",
            "sam@example.com" to "EMAIL",
            "Visa ••••6222" to "CARD",
        ).forEach { (value, shape) ->
            assertEquals("$value ($shape)", FilterStep.PII_SHAPE, SkeletonBuilder.withholdingStep(value, null))
        }
        // BARE_STREET is WHOLE-VALUE: embedded in a longer value it does not fire.
        assertNull(SkeletonBuilder.withholdingStep("29 items", null))
        assertNull(SkeletonBuilder.withholdingStep("Pickup instructions", null))
    }

    // ---- Whole-frame behaviour ---------------------------------------------------------------------

    private fun tree(vararg texts: String) = UiNode(
        className = "android.widget.FrameLayout",
        children = texts.map { UiNode(text = it, className = "android.widget.TextView") },
    ).restoreParents()

    @Test
    fun `a sensitive frame yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Transfer out", "\$45.66 available"), null, meta, "doordash", "2026-09-30")
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_FRAME), out)
        assertNull(SkeletonBuilder.build(tree("Transfer", "\$45.66"), null, meta, "doordash", "2026-09-30"))
    }

    @Test
    fun `a sensitive window title yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), "DasherDirect", meta, "doordash", "2026-09-30")
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_TITLE), out)
    }

    @Test
    fun `an oversize item yields no skeleton`() {
        val big = UiNode(
            className = "android.widget.FrameLayout",
            children = (1..3000).map {
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/row_$it", text = "Row")
            },
        ).restoreParents()
        assertEquals(Outcome.Refused(Refusal.OVERSIZE), SkeletonBuilder.outcome(big, null, meta, "doordash", "2026-09-30"))
    }

    @Test
    fun `malformed envelope inputs are refused, never shipped`() {
        assertEquals(
            Outcome.Refused(Refusal.INVALID_ENVELOPE),
            SkeletonBuilder.outcome(tree("Continue"), null, meta, "doordash", "2026-09-30 12:04"),
        )
        assertEquals(
            Outcome.Refused(Refusal.INVALID_ENVELOPE),
            SkeletonBuilder.outcome(tree("Continue"), null, meta, "Jane Smith", "2026-09-30"),
        )
    }

    @Test
    fun `a built skeleton carries the envelope, the title slot and every text field by wire key`() {
        val node = UiNode(
            className = "android.widget.Button",
            viewIdResourceName = "com.x:id/cta",
            text = "Accept",
            contentDescription = "Accept offer",
            stateDescription = "  ",
            hintText = "Jane S",
            uniqueId = "store_Chipotle",
            isClickable = true,
            isEnabled = true,
            isChecked = 2,
        )
        val item = SkeletonBuilder.build(UiNode(children = listOf(node)).restoreParents(), "Offer", meta, "doordash", "2026-09-30")
        assertNotNull(item!!)
        assertEquals(SkeletonSchema.SCHEMA_ID, item.schemaId)
        assertEquals(1, item.hashDomain)
        assertEquals(SkeletonBuilder.FILTER_REV, item.filterRev)
        assertEquals("8.97.8", item.platformAppVersion)
        assertEquals("0.9.0+abc1234", item.appVersion)
        assertEquals("rules-2026.09.30", item.rulesetReleaseTag)
        assertEquals(7, item.engineVersion)
        assertEquals(2, item.rulesetFormatVersion)
        assertEquals(CensusFingerprint.of(item.root), item.fingerprint)
        assertEquals(words(1, "Offer"), item.windowTitle)
        val child = item.root.children.single()
        assertEquals(true, child.isClickable)
        assertEquals(2, child.isChecked)
        assertEquals(
            mapOf(
                UiNodeTextField.TEXT.wire to words(1, "Accept"),
                UiNodeTextField.CONTENT_DESCRIPTION.wire to words(2, "Accept offer"),
                UiNodeTextField.HINT_TEXT.wire to TextSlot.WITHHELD,
                // uid is a text slot like any other: hashed through the filter, never sent clear.
                UiNodeTextField.UNIQUE_ID.wire to words(1, "store_Chipotle"),
            ),
            child.text,
        )
        val json = SkeletonSchema.serialize(item)
        listOf("Accept", "Chipotle", "Jane", "Offer", "should/never/leave").forEach {
            assertTrue("'$it' must not appear in $json", !json.contains(it))
        }
    }
}
