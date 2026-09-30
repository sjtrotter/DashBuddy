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

    /** One field's slot, through the whole builder (the per-field path is private, review CC5). */
    private fun slot(value: String?, id: String? = null): TextSlot? =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = id, text = value),
            null, meta, "doordash", "2026-09-30",
        )!!.root.text["text"]
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
        assertEquals(FilterStep.PII_ID_INTAKE, SkeletonBuilder.withholdingStep("Accept", "com.x:id/order_cx_name"))
        assertEquals(FilterStep.PII_ID_INTAKE, SkeletonBuilder.withholdingStep("Accept", "com.x:id/tvTitle"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/tvTitle"))
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
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Call Jane S. now", null))
        // A lowercase WHOLE-VALUE name is still caught, by the anchored redact-side pattern.
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("jordan t", null))
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

    // ---- #1160 review round 1 ----------------------------------------------------------------------

    @Test
    fun `AA3 - an article or pronoun a is not an initial - chrome phrases hash`() {
        listOf(
            "Take a photo", "Report a problem", "Send a message", "Add a tip", "Leave a note",
            "Choose a reason", "Enter a code", "Start a Dash", "Tap a store",
        ).forEach { phrase ->
            val n = phrase.split(' ').size
            assertEquals(phrase, words(n, phrase), slot(phrase))
        }
        assertEquals(TextSlot.WITHHELD, slot("Jane S is waiting at the door"))
    }

    @Test
    fun `AA1 - a value withheld anywhere in the frame is withheld everywhere in it`() {
        // The codex fixture: the parent repeats the child's customer name, and only the child's id marks it.
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/row",
            contentDescription = "Sam",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam"),
                UiNode(className = "android.widget.TextView", text = "Sam "),
                UiNode(className = "android.widget.TextView", text = "Accept"),
            ),
        ).restoreParents()
        // In isolation "Sam" is words:1 and would hash.
        assertEquals(words(1, "Sam"), slot("Sam"))
        val item = SkeletonBuilder.build(frame, "Sam", meta, "doordash", "2026-09-30")!!
        assertEquals(TextSlot.WITHHELD, item.root.text.getValue("desc"))
        assertEquals(TextSlot.WITHHELD, item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.windowTitle)
        assertEquals(words(1, "Accept"), item.root.children[2].text.getValue("text"))
    }

    @Test
    fun `AA4 - a pathological deep tree is refused, never thrown`() {
        var node = UiNode(className = "android.widget.TextView", text = "Accept")
        repeat(200_000) { node = UiNode(className = "android.widget.ScrollView", viewIdResourceName = "x:id/n", children = listOf(node)) }
        // Never a throw. Which stage overflows first is JVM-stack dependent: the sensitive-marker scan
        // fails CLOSED on its own (its throw sentinel reads as a hit → SENSITIVE_FRAME); anything past
        // it lands in the whole-build catch (BUILD_FAILED). Both are refusals with no skeleton.
        val out = SkeletonBuilder.outcome(node, null, meta, "doordash", "2026-09-30")
        assertTrue("$out", out == Outcome.Refused(Refusal.BUILD_FAILED) || out == Outcome.Refused(Refusal.SENSITIVE_FRAME))
        assertNull(SkeletonBuilder.build(node, null, meta, "doordash", "2026-09-30"))
    }

    @Test
    fun `AA2 BB1 BB2 - a NUL or malformed UTF-16 in a class or id refuses the tree`() {
        listOf(
            UiNode(className = "android.widget.TextView\u0000N", text = "Accept"),
            // Review BB2: a NUL-bearing ID must refuse too, not be dropped to null by the grammar gate.
            UiNode(className = "android.widget.TextView", viewIdResourceName = "x:id/a\u0000", text = "Accept"),
            // Review BB1: a lone surrogate (class or id) is malformed UTF-16.
            UiNode(className = "\uD800", text = "Accept"),
            UiNode(className = "android.widget.TextView", viewIdResourceName = "x:id/a\uDC00", text = "Accept"),
        ).forEach { bad ->
            val child = UiNode(className = "android.widget.FrameLayout", viewIdResourceName = "x:id/host", children = listOf(bad))
            assertEquals(Outcome.Refused(Refusal.INVALID_TREE), SkeletonBuilder.outcome(child, null, meta, "doordash", "2026-09-30"))
        }
    }

    @Test
    fun `AA7 - Built carries the canonical JSON it measured`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), null, meta, "doordash", "2026-09-30") as Outcome.Built
        assertEquals(SkeletonSchema.serialize(out.skeleton), out.json)
        assertEquals(out.json.toByteArray(Charsets.UTF_8).size, out.itemBytes)
    }

    @Test
    fun `AA10 - a dynamic test-tag id is absent on the wire and in the fingerprint`() {
        fun frame(tag: String) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.doordash.driverapp:id/drop_off_step_instructions_activity_host_fragment",
            children = listOf(UiNode(className = "android.widget.Button", viewIdResourceName = tag, text = "Continue")),
        ).restoreParents()
        val a = SkeletonBuilder.build(frame("PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a"), null, meta, "doordash", "2026-09-30")!!
        val b = SkeletonBuilder.build(frame("PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef"), null, meta, "doordash", "2026-09-30")!!
        assertNull(a.root.children.single().id)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals("com.doordash.driverapp:id/drop_off_step_instructions_activity_host_fragment", a.root.id)
        // The §2 PII-id step still sees the RAW id: a dynamic id ending in a PII suffix withholds.
        assertEquals(FilterStep.PII_ID, SkeletonBuilder.withholdingStep("Sam", "x:id/row_1234_customer_name"))
    }

    // ---- #1160 review round 2 ----------------------------------------------------------------------

    @Test
    fun `CC1 - a non-static class name is absent on the wire and in the fingerprint`() {
        fun frame(cls: String) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.x:id/host",
            children = listOf(UiNode(className = cls, text = "Continue")),
        )
        val dynamic = SkeletonBuilder.build(frame("Composable_3f488d4a"), null, meta, "doordash", "2026-09-30")!!
        val free = SkeletonBuilder.build(frame("Jane Smith's button"), null, meta, "doordash", "2026-09-30")!!
        assertNull(dynamic.root.children.single().className)
        assertNull(free.root.children.single().className)
        assertEquals(dynamic.fingerprint, free.fingerprint)
        val static = SkeletonBuilder.build(frame("androidx.compose.ui.platform.ComposeView"), null, meta, "doordash", "2026-09-30")!!
        assertEquals("androidx.compose.ui.platform.ComposeView", static.root.children.single().className)
    }

    @Test
    fun `CC2 - an over-long optional stamp is truncated, never a refusal`() {
        val long = meta.copy(platformAppVersion = "9".repeat(90), rulesetReleaseTag = "r".repeat(70))
        val out = SkeletonBuilder.outcome(tree("Continue"), null, long, "doordash", "2026-09-30")
        assertTrue("$out", out is Outcome.Built)
        val item = (out as Outcome.Built).skeleton
        assertEquals("9".repeat(64), item.platformAppVersion)
        assertEquals("r".repeat(64), item.rulesetReleaseTag)
        // A truncation that would split a surrogate pair drops the stamp rather than ship malformed text.
        val split = meta.copy(appVersion = "a".repeat(63) + "\uD801\uDC00")
        assertNull(SkeletonBuilder.build(tree("Continue"), null, split, "doordash", "2026-09-30")!!.appVersion)
    }

    @Test
    fun `CC3 - an intake-only PII id withholds its own field but does not seed the frame`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Hand it to me"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/step_description", text = "Hand it to me"),
            ),
        )
        val item = SkeletonBuilder.build(frame, null, meta, "doordash", "2026-09-30")!!
        assertEquals(words(4, "Hand it to me"), item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        // ...while an ID_MARKERS id (value IS PII) still propagates frame-wide (the AA1 fixture shape).
        val pii = UiNode(
            className = "android.widget.LinearLayout",
            contentDescription = "Sam",
            children = listOf(UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam")),
        )
        assertEquals(TextSlot.WITHHELD, SkeletonBuilder.build(pii, null, meta, "doordash", "2026-09-30")!!.root.text.getValue("desc"))
    }
}
