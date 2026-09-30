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
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
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
    private val platform = Platform.DoorDash
    private val day: LocalDate = LocalDate.of(2026, 9, 30)

    private fun slot(value: String?, id: String? = null): TextSlot? =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.TextView", viewIdResourceName = id, text = value),
            null, meta, platform, day,
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

    // Step 1 lives only in the builder's IdClass (review GG4), so it is exercised through `outcome()`.

    @Test
    fun `step 1 - an ID_MARKERS suffix withholds, case-insensitively, on the full id`() {
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.doordash.driverapp:id/customer_name"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/ORDER_CUSTOMER_NAME"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/description_text_view"))
    }

    @Test
    fun `step 1 - a PII_ID_SUFFIXES exact suffix withholds`() {
        // `order_cx_name` and `tvTitle` are PII_ID_SUFFIXES only (not ID_MARKERS) — the union is used.
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/order_cx_name"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/tvTitle"))
    }

    @Test
    fun `step 1 - negative - a chrome id hashes`() {
        assertEquals(words(1, "Accept"), slot("Accept", "com.x:id/accept_button"))
        // PII_ID_SUFFIXES is EXACT after the last slash, so a longer id ending differently is chrome.
        assertEquals(words(1, "Accept"), slot("Accept", "com.x:id/tvTitleLabel"))
    }

    // ---- Step 2: length cap ------------------------------------------------------------------------

    @Test
    fun `step 2 - over 40 characters withholds, 40 does not`() {
        val forty = "Aaaa bbbb cccc dddd eeee ffff gggg hhhhh"
        assertEquals(40, forty.length)
        assertNull(SkeletonBuilder.withholdingStep(forty))
        assertEquals(FilterStep.LENGTH_CAP, SkeletonBuilder.withholdingStep(forty + "h"))
    }

    // ---- Step 3: CustomerTextMarkers ---------------------------------------------------------------

    @Test
    fun `step 3 - a customer marker withholds`() {
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Delivery to Morgan"))
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Meet at door for Sam"))
        assertNull(SkeletonBuilder.withholdingStep("Delivery details"))
    }

    // ---- Step 4: customerLeadIn ------------------------------------------------------------------

    @Test
    fun `step 4 - a lead-in with a tail withholds, incl the gated Return prefix`() {
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Pickup from Chipotle"))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Heading to Walmart"))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Return Riley P to H-E-B"))
        // The gate's negative: DoorDash's own button is chrome.
        assertNull(SkeletonBuilder.withholdingStep("Return to dash"))
    }

    // ---- Step 5: masks -----------------------------------------------------------------------------

    @Test
    fun `step 5 - any mask anywhere withholds`() {
        listOf("[address]", "Call [phone]", "[redacted]", "[redacted:ab12]", "\"[note]\"", "Hi [name]")
            .forEach { assertEquals(it, FilterStep.MASK, SkeletonBuilder.withholdingStep(it)) }
        // Step order: a marker-led masked value is caught at step 3 before step 5 sees it.
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Verify items for [name]"))
        // `[icon]` is DoorDash chrome, not a mask.
        assertNull(SkeletonBuilder.withholdingStep("[icon] Gold"))
    }

    // ---- Step 6: only words:N hash (non-withholding; steps 7–8 still run) ----------------------------

    @Test
    fun `step 6 - a mixed token continues to step 8 and is withheld there`() {
        assertEquals(FilterStep.PII_SHAPE, SkeletonBuilder.withholdingStep("Apt 12"))
        assertEquals(TextSlot(kind = "mixed"), slot("Order #1234"))
    }

    // ---- Step 7: the embedded name shape -----------------------------------------------------------

    @Test
    fun `step 7 - an embedded first-name last-initial withholds`() {
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Jane S is waiting at the door"))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Brandon C"))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("Call Jane S. now"))
        // A lowercase WHOLE-VALUE name is still caught, by the anchored redact-side pattern.
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("jordan t"))
        assertEquals(FilterStep.NAME_SHAPE, SkeletonBuilder.withholdingStep("José  R"))
        assertNull(SkeletonBuilder.withholdingStep("Hand it to me"))
        assertNull(SkeletonBuilder.withholdingStep("Confirm pickup"))
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
            assertEquals("$value ($shape)", FilterStep.PII_SHAPE, SkeletonBuilder.withholdingStep(value))
        }
        // BARE_STREET is WHOLE-VALUE: embedded in a longer value it does not fire.
        assertNull(SkeletonBuilder.withholdingStep("29 items"))
        assertNull(SkeletonBuilder.withholdingStep("Pickup instructions"))
    }

    // ---- Whole-frame behaviour ---------------------------------------------------------------------

    private fun tree(vararg texts: String) = UiNode(
        className = "android.widget.FrameLayout",
        children = texts.map { UiNode(text = it, className = "android.widget.TextView") },
    ).restoreParents()

    @Test
    fun `a sensitive frame yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Transfer out", "\$45.66 available"), null, meta, platform, day)
        assertEquals(Outcome.Refused(Refusal.SENSITIVE_FRAME), out)
        assertNull(SkeletonBuilder.build(tree("Transfer", "\$45.66"), null, meta, platform, day))
    }

    @Test
    fun `a sensitive window title yields no skeleton`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), "DasherDirect", meta, platform, day)
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
        assertEquals(Outcome.Refused(Refusal.OVERSIZE), SkeletonBuilder.outcome(big, null, meta, platform, day))
    }

    @Test
    fun `the typed API emits the wire forms of platform and day (review GG6)`() {
        val item = SkeletonBuilder.build(tree("Continue"), null, meta, Platform.Uber, LocalDate.of(2026, 2, 3))!!
        assertEquals("uber", item.platform)
        assertEquals("2026-02-03", item.day)
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
        val item = SkeletonBuilder.build(UiNode(children = listOf(node)).restoreParents(), "Offer", meta, platform, day)
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
        val item = SkeletonBuilder.build(frame, "Sam", meta, platform, day)!!
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
        // Never a throw, and never mis-reported as a sensitive frame (review GG3): whichever stage
        // overflows first — the marker scan (its fail-closed sentinel) or anything after it — the
        // outcome is BUILD_FAILED.
        val out = SkeletonBuilder.outcome(node, null, meta, platform, day)
        assertEquals(Outcome.Refused(Refusal.BUILD_FAILED), out)
        assertNull(SkeletonBuilder.build(node, null, meta, platform, day))
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
            assertEquals(Outcome.Refused(Refusal.INVALID_TREE), SkeletonBuilder.outcome(child, null, meta, platform, day))
        }
    }

    @Test
    fun `AA7 - Built carries the canonical JSON it measured`() {
        val out = SkeletonBuilder.outcome(tree("Continue"), null, meta, platform, day) as Outcome.Built
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
        val a = SkeletonBuilder.build(frame("PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a"), null, meta, platform, day)!!
        val b = SkeletonBuilder.build(frame("PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef"), null, meta, platform, day)!!
        assertNull(a.root.children.single().id)
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals("com.doordash.driverapp:id/drop_off_step_instructions_activity_host_fragment", a.root.id)
        // The §2 PII-id step still sees the RAW id: a dynamic id ending in a PII suffix withholds.
        assertEquals(TextSlot.WITHHELD, slot("Sam", "x:id/row_1234_customer_name"))
    }

    // ---- #1160 review round 2 ----------------------------------------------------------------------

    @Test
    fun `CC1 - a non-static class name is absent on the wire and in the fingerprint`() {
        fun frame(cls: String) = UiNode(
            className = "android.widget.FrameLayout",
            viewIdResourceName = "com.x:id/host",
            children = listOf(UiNode(className = cls, text = "Continue")),
        )
        val dynamic = SkeletonBuilder.build(frame("Composable_3f488d4a"), null, meta, platform, day)!!
        val free = SkeletonBuilder.build(frame("Jane Smith's button"), null, meta, platform, day)!!
        assertNull(dynamic.root.children.single().className)
        assertNull(free.root.children.single().className)
        assertEquals(dynamic.fingerprint, free.fingerprint)
        val static = SkeletonBuilder.build(frame("androidx.compose.ui.platform.ComposeView"), null, meta, platform, day)!!
        assertEquals("androidx.compose.ui.platform.ComposeView", static.root.children.single().className)
    }

    @Test
    fun `CC2 - an over-long optional stamp is truncated, never a refusal`() {
        val long = meta.copy(platformAppVersion = "9".repeat(90), rulesetReleaseTag = "r".repeat(70))
        val out = SkeletonBuilder.outcome(tree("Continue"), null, long, platform, day)
        assertTrue("$out", out is Outcome.Built)
        val item = (out as Outcome.Built).skeleton
        assertEquals("9".repeat(64), item.platformAppVersion)
        assertEquals("r".repeat(64), item.rulesetReleaseTag)
        // A truncation that would split a surrogate pair drops the stamp rather than ship malformed text.
        val split = meta.copy(appVersion = "a".repeat(63) + "\uD801\uDC00")
        assertNull(SkeletonBuilder.build(tree("Continue"), null, split, platform, day)!!.appVersion)
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
        val item = SkeletonBuilder.build(frame, null, meta, platform, day)!!
        assertEquals(words(4, "Hand it to me"), item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        // ...while an ID_MARKERS id (value IS PII) still propagates frame-wide (the AA1 fixture shape).
        val pii = UiNode(
            className = "android.widget.LinearLayout",
            contentDescription = "Sam",
            children = listOf(UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam")),
        )
        assertEquals(TextSlot.WITHHELD, SkeletonBuilder.build(pii, null, meta, platform, day)!!.root.text.getValue("desc"))
    }

    // ---- #1160 review round 3 ----------------------------------------------------------------------

    @Test
    fun `DD2 - the value filter runs ONCE per distinct trimmed value across both passes`() {
        val counts = HashMap<String, Int>()
        val filter = SkeletonBuilder.FrameFilter(judge = { v ->
            counts.merge(v, 1, Int::plus)
            SkeletonBuilder.withholdingStep(v)
        })
        val tree = UiNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Accept", contentDescription = " Accept "),
                UiNode(className = "android.widget.TextView", text = "Accept", hintText = "Jane S"),
                UiNode(className = "android.widget.TextView", text = "Jane S"),
            ),
        )
        val pending = filter.scan(tree)
        val title = filter.field("Accept", SkeletonBuilder.IdClass.NONE)!!
        val root = filter.emit(pending)
        filter.slot(title)
        assertEquals(words(1, "Accept"), root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, root.children[2].text.getValue("text"))
        assertEquals(mapOf("Accept" to 1, "Jane S" to 1), counts)
    }

    @Test
    fun `EE1 - a CONTENT id does not seed the frame, and an identity id seeds only from text and desc`() {
        val content = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", text = "Required"),
                UiNode(className = "android.widget.TextView", text = "Raise to 50%"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/description_text_view", text = "Required"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/description_text_view", text = "Raise to 50%"),
            ),
        )
        val item = SkeletonBuilder.build(content, null, meta, platform, day)!!
        assertEquals(words(1, "Required"), item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot(kind = "mixed"), item.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[2].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[3].text.getValue("text"))

        val role = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam", roleDescription = "Button"),
                UiNode(className = "android.widget.Button", text = "Button", contentDescription = "Sam"),
            ),
        )
        val r = SkeletonBuilder.build(role, null, meta, platform, day)!!
        // The identity id's own fields are all withheld (step 1)...
        assertEquals(TextSlot.WITHHELD, r.root.children[0].text.getValue("role"))
        // ...but only its TEXT seeds the frame: "Sam" propagates, the role's "Button" does not.
        assertEquals(TextSlot.WITHHELD, r.root.children[1].text.getValue("desc"))
        assertEquals(words(1, "Button"), r.root.children[1].text.getValue("text"))
    }

    @Test
    fun `EE2 - whitespace is normalized once, so both regex engines agree on withhold and hash`() {
        assertEquals(TextSlot.WITHHELD, slot("José\u00A0R"))
        assertEquals(TextSlot.WITHHELD, slot("Jane\u2009S is waiting at the door"))
        assertEquals(words(2, "Pickup & delivery"), slot("Pickup\u00A0&\u00A0delivery"))
        assertEquals(words(2, "Pickup & delivery"), slot("  Pickup \t &\n delivery "))
    }

    // ---- #1160 review round 4 ----------------------------------------------------------------------

    @Test
    fun `FF1 - the value steps judge BOTH the raw trimmed and the canonical form`() {
        // Raw-only hit: QUOTED_NOTE needs 6+ characters between the quotes; canonicalization shrinks
        // "ab  cd" to "ab cd". Pre-FF1 this hashed as words:2.
        assertEquals(TextSlot.WITHHELD, slot("\"ab  cd\""))
        // Canonical-only hit: an NBSP-split name — the JVM regex's \s excludes NBSP, so only the
        // canonical form carries the name shape.
        assertEquals(TextSlot.WITHHELD, slot("Brandon\u00A0C"))
        // Neither: chrome still hashes on its canonical form.
        assertEquals(words(2, "Pickup & delivery"), slot("Pickup  &  delivery"))
    }

    @Test
    fun `GG1 - an identity value embedded in an id-less node is withheld by token containment`() {
        fun frame(vararg others: String) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Adam"),
            ) + others.map { UiNode(className = "android.widget.TextView", text = it) },
        )
        val item = SkeletonBuilder.build(frame("Adam's order", "Adam, 2 items", "ADAM", "Add a tip", "Adamant"), null, meta, platform, day)!!
        val slots = item.root.children.drop(1).map { it.text.getValue("text") }
        assertEquals(TextSlot.WITHHELD, slots[0])
        assertEquals(TextSlot.WITHHELD, slots[1])
        assertEquals(TextSlot.WITHHELD, slots[2])
        assertEquals(words(3, "Add a tip"), slots[3])
        // A different run (a longer word) is not the identity token.
        assertEquals(words(1, "Adamant"), slots[4])
        // Review II1: a two-letter identity value seeds its run too ("Jo's pick" is withheld).
        val jo = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Jo"),
                UiNode(className = "android.widget.TextView", text = "Jo's pick"),
                UiNode(className = "android.widget.TextView", text = "Jo"),
            )),
            null, meta, platform, day,
        )!!
        assertEquals(TextSlot.WITHHELD, jo.root.children[1].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, jo.root.children[2].text.getValue("text"))
    }

    @Test
    fun `GG2 - the cap is the canonical form's, and the raw pass runs only within the cap`() {
        // Wide-spaced chrome: raw 46 chars, canonical 17 — hashes (the raw padding is not capped).
        val wide = "Pickup" + " ".repeat(20) + "&" + " ".repeat(13) + "delivery"
        assertTrue(wide.length > SkeletonBuilder.MAX_TOKEN_LENGTH)
        assertEquals(words(2, "Pickup & delivery"), slot(wide))
        // A padded customer marker: raw over the cap, canonical "Deliver to Sam" — withheld AND seeds.
        val padded = "Deliver" + " ".repeat(20) + "to" + " ".repeat(20) + "Sam"
        assertTrue(padded.length > SkeletonBuilder.MAX_TOKEN_LENGTH)
        val item = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", text = padded),
                UiNode(className = "android.widget.TextView", text = "Deliver to Sam"),
            )),
            null, meta, platform, day,
        )!!
        assertEquals(TextSlot.WITHHELD, item.root.children[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, item.root.children[1].text.getValue("text"))
        // The FF1 reproducer (raw ≤ 40) is still caught on the raw pass.
        assertEquals(TextSlot.WITHHELD, slot("\"ab  cd\""))
    }

    // ---- #1160 review round 5 ----------------------------------------------------------------------

    /** A frame with a `customer_name` identity value and id-less siblings; returns the siblings' slots. */
    private fun beside(identity: String, vararg others: String): List<TextSlot> =
        SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = identity),
            ) + others.map { UiNode(className = "android.widget.TextView", text = it) }),
            null, meta, platform, day,
        )!!.root.children.drop(1).map { it.text.getValue("text") }

    @Test
    fun `HH1 - identity containment uses one Unicode case fold`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("ΝΙΚΟΣ", "νικος's order"))
        assertEquals(listOf(TextSlot.WITHHELD), beside("Groß", "GROSS's order"))
    }

    @Test
    fun `HH2 - the seed threshold counts letter code points`() {
        // ONE supplementary-plane letter: two UTF-16 units, but one letter — seeds no run (II1: the
        // threshold is two letters).
        val one = "\uD801\uDC00"
        assertEquals(listOf(words(2, "$one pick"), TextSlot.WITHHELD), beside(one, "$one pick", one))
        // Two such letters (four units) do seed.
        val pair = one + "\uD801\uDC01"
        assertEquals(listOf(TextSlot.WITHHELD), beside(pair, "$pair pick"))
    }

    @Test
    fun `HH4 - identity-dependent chrome suppression - a common first name withholds same-word chrome`() {
        // The documented cost of GG1's containment rule (ADR §7 / residual risk 9).
        assertEquals(listOf(TextSlot.WITHHELD), beside("May", "May need returns"))
        assertEquals(listOf(words(3, "May need returns")), beside("Sam", "May need returns"))
    }

    @Test
    fun `HH5 - a raw pattern match beyond the cap does not prevent hashing (the bounded raw pass)`() {
        // Raw: a quoted note (QUOTED_NOTE), but 46 characters — the raw pass is skipped by design (GG2);
        // canonical: "ab cd" in quotes, too short for QUOTED_NOTE — hashes as words:2.
        val raw = "\"ab" + " ".repeat(40) + "cd\""
        assertTrue(raw.length > SkeletonBuilder.MAX_TOKEN_LENGTH)
        assertEquals(words(2, "\"ab cd\""), slot(raw))
    }

    @Test
    fun `II1 - a two-letter first name is covered, a single letter seeds nothing`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("Li", "Li's order"))
        // Whole-run equality: "li" does not match inside another word.
        assertEquals(listOf(words(2, "Limited offer")), beside("Li", "Limited offer"))
        assertEquals(listOf(words(2, "A tip")), beside("A", "A tip"))
    }

    @Test
    fun `II3 - a name-bearing test-tag id is absent, chrome ids travel`() {
        listOf("row_Deliver_to_Sam", "com.x:id/chip_Adam_S", "Pickup_for_Sam")
            .forEach { assertTrue(it, !SkeletonBuilder.isStaticId(it)) }
        listOf("bc25_fab", "a11y_clock", "Artwork Image", "com.doordash.driverapp:id/customer_name", "Tooltip-0")
            .forEach { assertTrue(it, SkeletonBuilder.isStaticId(it)) }
        val item = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", viewIdResourceName = "row_Deliver_to_Sam", text = "Go"),
            null, meta, platform, day,
        )!!
        assertNull(item.root.id)
    }

    @Test
    fun `II5 - a builder defect is BUILD_FAILED, never INVALID_TREE`() {
        val defect = SkeletonBuilder.FrameFilter(
            judge = { SkeletonBuilder.withholdingStep(it) },
            unfiltered = { throw IllegalArgumentException("a bad TextSlot") },
        )
        assertEquals(
            Outcome.Refused(Refusal.BUILD_FAILED),
            SkeletonBuilder.outcome(tree("Continue"), null, meta, platform, day, defect),
        )
    }

    @Test
    fun `II6 - a corrupt tri-state is refused as INVALID_TREE, not laundered to unchecked`() {
        val bad = UiNode(className = "android.widget.CheckBox", text = "Leave at door", isChecked = 7)
        assertEquals(Outcome.Refused(Refusal.INVALID_TREE), SkeletonBuilder.outcome(bad, null, meta, platform, day))
    }

    @Test
    fun `JJ1 - an id carrying the frame's identity run is absent for the wire and the fingerprint`() {
        fun frame(tag: String?) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/row",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Adam"),
                UiNode(className = "android.widget.Button", viewIdResourceName = tag, text = "Continue"),
            ),
        )
        val adam = SkeletonBuilder.build(frame("com.x:id/chip_Adam"), null, meta, platform, day)!!
        assertNull(adam.root.children[1].id)
        val gold = SkeletonBuilder.build(frame("com.x:id/chip_Gold"), null, meta, platform, day)!!
        assertEquals("com.x:id/chip_Gold", gold.root.children[1].id)
        val none = SkeletonBuilder.build(frame(null), null, meta, platform, day)!!
        assertEquals(none.fingerprint, adam.fingerprint)
        // Without an identity id on the frame, the same tag travels (ADR residual risk 10).
        val alone = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", viewIdResourceName = "com.x:id/chip_Adam", text = "Continue"),
            null, meta, platform, day,
        )!!
        assertEquals("com.x:id/chip_Adam", alone.root.id)
    }

    @Test
    fun `KK1 - ids and classes split at camelCase boundaries, text slots do not`() {
        fun tagOf(tag: String): String? = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "Adam"),
                UiNode(className = "android.widget.Button", viewIdResourceName = "com.x:id/$tag", text = "Continue"),
            )),
            null, meta, platform, day,
        )!!.root.children[1].id
        assertNull(tagOf("chipAdam"))
        assertNull(tagOf("XMLAdamRow"))
        assertNull(tagOf("chip_adam"))
        assertEquals("com.x:id/chipAdamant", tagOf("chipAdamant"))
        assertEquals("com.x:id/chipGold", tagOf("chipGold"))
        // A text slot keeps the plain letter-run split: "chipAdam" is one run, not the identity run.
        assertEquals(listOf(words(1, "chipAdam")), beside("Adam", "chipAdam"))
    }

    @Test
    fun `LL1 - an ADDRESS seeds its exact value only, a NAME its runs too, a mask nothing`() {
        fun frame(identityId: String, identity: String, vararg nodes: UiNode) = UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.x:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/$identityId", text = identity),
            ) + nodes,
        )
        val address = SkeletonBuilder.build(
            frame(
                "address_line_1", "Bay View Commons",
                UiNode(className = "android.widget.TextView", text = "View details"),
                UiNode(className = "android.widget.TextView", text = "Bay View Commons"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/roadNameLayout"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/bayViewIcon"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(words(2, "View details"), address[1].text.getValue("text"))
        // AA1's exact-duplicate rule still holds for an address.
        assertEquals(TextSlot.WITHHELD, address[2].text.getValue("text"))
        assertEquals("com.x:id/roadNameLayout", address[3].id)
        assertEquals("com.x:id/bayViewIcon", address[4].id)

        // A mask seeds nothing — neither its exact value's words nor ids carrying "redacted"/"address".
        val masked = SkeletonBuilder.build(
            frame(
                "customer_name", "[redacted:ab12]",
                UiNode(className = "android.widget.TextView", text = "Redacted items"),
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/redactedBadge"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(words(2, "Redacted items"), masked[1].text.getValue("text"))
        assertEquals("com.x:id/redactedBadge", masked[2].id)
        val addressMask = SkeletonBuilder.build(
            frame(
                "address_line_1", "[address]",
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.x:id/addressIcon"),
            ),
            null, meta, platform, day,
        )!!.root.children
        assertEquals("com.x:id/addressIcon", addressMask[1].id)

        // A NAME still seeds runs: "Adam's order" and `chipAdam` beside `customer_name` "Adam".
        assertEquals(listOf(TextSlot.WITHHELD), beside("Adam", "Adam's order"))
    }

    // ---- #1160 review round 6 ----------------------------------------------------------------------

    @Test
    fun `MM1 - a name with internal capitals matches across contiguous camel segments`() {
        fun node(id: String?, cls: String = "android.widget.Button") =
            UiNode(className = cls, viewIdResourceName = id, text = "Continue")
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", text = "McKenna"),
                node("com.x:id/chip_McKenna"),
                node("com.x:id/chipMcKenna"),
                node(null, cls = "com.x.McKennaButton"),
                node("com.x:id/chipMcGold"),
                UiNode(className = "android.widget.TextView", text = "McKenna's order"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertNull(out[1].id)
        assertNull(out[2].id)
        assertNull(out[3].className)
        assertEquals("com.x:id/chipMcGold", out[4].id)
        assertEquals(TextSlot.WITHHELD, out[5].text.getValue("text"))
    }

    @Test
    fun `MM2 - a capital sharp S matches its small form`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("Groß", "GRO\u1E9E"))
        assertEquals(listOf(TextSlot.WITHHELD), beside("GRO\u1E9E", "Groß"))
    }

    @Test
    fun `NN1 NN2 - user_name and order_cx_name are NAME ids that seed the frame`() {
        fun frame(id: String) = UiNode(
            className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/$id", text = "Riley"),
                UiNode(className = "android.widget.TextView", text = "Riley"),
                UiNode(className = "android.widget.TextView", text = "Call Riley"),
            ),
        )
        listOf("user_name", "order_cx_name").forEach { id ->
            val out = SkeletonBuilder.build(frame(id), null, meta, platform, day)!!.root.children
            assertEquals(id, TextSlot.WITHHELD, out[1].text.getValue("text"))
            assertEquals(id, TextSlot.WITHHELD, out[2].text.getValue("text"))
        }
    }

    @Test
    fun `NN3 - a NAME desc seeds only the runs its text shares`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(
                    className = "android.widget.TextView",
                    viewIdResourceName = "com.x:id/customer_name",
                    text = "Adam",
                    contentDescription = "Customer name Adam",
                ),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name_label", text = "Customer"),
                UiNode(className = "android.widget.TextView", text = "Name"),
                UiNode(className = "android.widget.TextView", text = "Adam's order"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals("com.x:id/customer_name", out[0].id)
        assertEquals("com.x:id/customer_name_label", out[1].id)
        assertEquals(words(1, "Customer"), out[1].text.getValue("text"))
        assertEquals(words(1, "Name"), out[2].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, out[3].text.getValue("text"))
        // A NAME with a blank text seeds its desc's EXACT value only.
        val descOnly = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/customer_name", contentDescription = "Customer name Adam"),
                UiNode(className = "android.widget.TextView", text = "Customer name Adam"),
                UiNode(className = "android.widget.TextView", text = "Customer"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(TextSlot.WITHHELD, descOnly[1].text.getValue("text"))
        assertEquals(words(1, "Customer"), descOnly[2].text.getValue("text"))
    }

    @Test
    fun `NN5 - one glyph fold defeats zero-width and fullwidth evasions`() {
        assertEquals(TextSlot.WITHHELD, slot("Deli\u200Bver to Sam"))
        assertEquals(TextSlot.WITHHELD, slot("\uFF24eliver to Sam"))
        // A fullwidth chrome word hashes equal to its plain twin (k can count them together).
        assertEquals(words(1, "Accept"), slot("\uFF21\uFF43\uFF43\uFF45\uFF50\uFF54"))
    }

    @Test
    fun `NN6 - an over-long id is refused before the shape regex`() {
        assertTrue(!SkeletonBuilder.isStaticId("com.x:id/" + "a".repeat(10_000)))
    }
}
