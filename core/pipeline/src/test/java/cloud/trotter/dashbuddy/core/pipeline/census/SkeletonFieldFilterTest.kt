package cloud.trotter.dashbuddy.core.pipeline.census
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.FilterStep
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Outcome
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §2 — the PER-FIELD filter: steps 1–8 in ADR order (canonical + bounded raw pass), the required vectors, and the whitespace / glyph / FORMAT evasions. Split from `SkeletonBuilderTest` (#1160 review UU10). */
class SkeletonFieldFilterTest : SkeletonBuilderTestBase() {

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

    @Test
    fun `step 1 - an ID_MARKERS suffix withholds, case-insensitively, on the full id`() {
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.doordash.driverapp:id/customer_name"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/ORDER_CUSTOMER_NAME"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/description_text_view"))
    }

    @Test
    fun `step 1 - a text input, and every node under one, withholds like a PII id row (#919, fable review)`() {
        val draft = "meet me at the side door code 7391"
        // Id-less EditText: the runtime UNKNOWN scrub masks it; the census must not ship it as word slots.
        val lone = SkeletonBuilder.build(
            UiNode(className = "android.widget.EditText", text = draft), null, meta, platform, day,
        )!!.root
        assertEquals(TextSlot.WITHHELD, lone.text["text"])
        // The flag alone (a Compose field reporting a generic class) withholds too.
        val flagged = SkeletonBuilder.build(
            UiNode(className = "android.view.View", isEditable = true, text = draft), null, meta, platform, day,
        )!!.root
        assertEquals(TextSlot.WITHHELD, flagged.text["text"])
        // A composite input: the child TextView under the EditText is owned by it; a sibling outside is not.
        val frame = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                UiNode(className = "android.widget.EditText", hintText = "Type a message", children = listOf(
                    UiNode(className = "android.widget.TextView", text = draft),
                )),
                UiNode(className = "android.widget.TextView", text = "Send"),
            )).restoreParents(),
            null, meta, platform, day,
        )!!.root
        assertEquals(TextSlot.WITHHELD, frame.children[0].children[0].text["text"])
        assertEquals(TextSlot.WITHHELD, frame.children[0].text["hint"])
        assertTrue(frame.children[1].text["text"] != TextSlot.WITHHELD)
    }

    @Test
    fun `step 1 - a table id or an intake-only id withholds its own field`() {
        // `order_cx_name` / `tvTitle` are ID_MARKER_TABLE rows (NN2, PP6); `chat_input_text_field` and
        // `primaryManeuverText` are the table's intake-only CONTENT/NEVER rows (AL3; #919 promoted
        // `message_input` to a runtime ALWAYS row, so it is no longer the example).
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/order_cx_name"))
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/tvTitle"))
    }

    @Test
    fun `step 1 - an intake-only CONTENT id withholds its own field and seeds nothing (reviews SS6, AL3)`() {
        listOf("chat_input_text_field", "primaryManeuverText").forEach { suffix ->
            val out = SkeletonBuilder.build(
                UiNode(className = "android.widget.LinearLayout", viewIdResourceName = "com.x:id/row", children = listOf(
                    UiNode(className = "android.widget.TextView", viewIdResourceName = "com.x:id/$suffix", text = "Turn right"),
                    UiNode(className = "android.widget.TextView", text = "Turn right"),
                )),
                null, meta, platform, day,
            )!!.root.children
            assertEquals(suffix, TextSlot.WITHHELD, out[0].text.getValue("text"))
            assertEquals(suffix, words(2, "Turn right"), out[1].text.getValue("text"))
        }
    }

    @Test
    fun `step 1 - negative - a chrome id hashes`() {
        assertEquals(words(1, "Accept"), slot("Accept", "com.x:id/accept_button"))
        // The table matches by SUFFIX (AL3), so a longer id ending differently is chrome …
        assertEquals(words(1, "Accept"), slot("Accept", "com.x:id/tvTitleLabel"))
        // … and an id that merely ENDS in a PII suffix is withheld (the widening toward privacy).
        assertEquals(TextSlot.WITHHELD, slot("Accept", "com.x:id/header_step_description"))
    }

    @Test
    fun `step 2 - over 40 characters withholds, 40 does not`() {
        val forty = "Aaaa bbbb cccc dddd eeee ffff gggg hhhhh"
        assertEquals(40, forty.length)
        assertNull(SkeletonBuilder.withholdingStep(forty))
        assertEquals(FilterStep.LENGTH_CAP, SkeletonBuilder.withholdingStep(forty + "h"))
    }

    @Test
    fun `step 3 - a customer marker withholds`() {
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Delivery to Morgan"))
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Meet at door for Sam"))
        assertNull(SkeletonBuilder.withholdingStep("Delivery details"))
    }

    @Test
    fun `step 4 - a lead-in with a tail withholds, incl the gated Return prefix`() {
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Pickup from Chipotle"))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Heading to Walmart"))
        assertEquals(FilterStep.LEAD_IN, SkeletonBuilder.withholdingStep("Return Riley P to H-E-B"))
        // The gate's negative: DoorDash's own button is chrome.
        assertNull(SkeletonBuilder.withholdingStep("Return to dash"))
    }

    @Test
    fun `step 5 - any mask anywhere withholds`() {
        listOf("[address]", "Call [phone]", "[redacted]", "[redacted:ab12]", "\"[note]\"", "Hi [name]")
            .forEach { assertEquals(it, FilterStep.MASK, SkeletonBuilder.withholdingStep(it)) }
        // Step order: a marker-led masked value is caught at step 3 before step 5 sees it.
        assertEquals(FilterStep.CUSTOMER_MARKER, SkeletonBuilder.withholdingStep("Verify items for [name]"))
        // `[icon]` is DoorDash chrome, not a mask.
        assertNull(SkeletonBuilder.withholdingStep("[icon] Gold"))
    }

    @Test
    fun `step 6 - a mixed token continues to step 8 and is withheld there`() {
        assertEquals(FilterStep.PII_SHAPE, SkeletonBuilder.withholdingStep("Apt 12"))
        assertEquals(TextSlot(kind = "mixed"), slot("Order #1234"))
    }

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
    fun `EE2 - whitespace is normalized once, so both regex engines agree on withhold and hash`() {
        assertEquals(TextSlot.WITHHELD, slot("José\u00A0R"))
        assertEquals(TextSlot.WITHHELD, slot("Jane\u2009S is waiting at the door"))
        assertEquals(words(2, "Pickup & delivery"), slot("Pickup\u00A0&\u00A0delivery"))
        assertEquals(words(2, "Pickup & delivery"), slot("  Pickup \t &\n delivery "))
    }

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

    @Test
    fun `HH5 - a raw pattern match beyond the cap does not prevent hashing (the bounded raw pass)`() {
        // Raw: a quoted note (QUOTED_NOTE), but 46 characters — the raw pass is skipped by design (GG2);
        // canonical: "ab cd" in quotes, too short for QUOTED_NOTE — hashes as words:2.
        val raw = "\"ab" + " ".repeat(40) + "cd\""
        assertTrue(raw.length > SkeletonBuilder.MAX_TOKEN_LENGTH)
        assertEquals(words(2, "\"ab cd\""), slot(raw))
    }

    @Test
    fun `NN5 - one glyph fold defeats zero-width and fullwidth evasions`() {
        assertEquals(TextSlot.WITHHELD, slot("Deli\u200Bver to Sam"))
        assertEquals(TextSlot.WITHHELD, slot("\uFF24eliver to Sam"))
        // A fullwidth chrome word hashes equal to its plain twin (k can count them together).
        assertEquals(words(1, "Accept"), slot("\uFF21\uFF43\uFF43\uFF45\uFF50\uFF54"))
    }

    @Test
    fun `OO1 - the judged form is the hashed form, and the ZWJ-hidden name is withheld`() {
        assertEquals(listOf(TextSlot.WITHHELD), beside("\u00C5dam", "A\u200D\u030Adam"))
        // Direct vs builder agreement on an NFKC-sensitive value.
        val ligature = "\uFB01nance"
        assertEquals(CensusHash.of(ligature), slot(ligature)!!.h)
        assertEquals(CensusHash.ofCanonical("finance"), slot(ligature)!!.h)
    }

    @Test
    fun `PP5 - a supplementary-plane FORMAT char cannot split a marker`() {
        assertEquals(TextSlot.WITHHELD, slot("Deli\uDB40\uDC20ver to Sam"))
    }

    @Test
    fun `RR1 - a supplementary FORMAT char beside an SSN still refuses the frame, and the raw pass keeps boundaries`() {
        assertEquals(
            Outcome.Refused(Refusal.SENSITIVE_FRAME),
            SkeletonBuilder.outcome(tree("x\uDB40\uDC20123-45-6789"), null, meta, platform, day),
        )
        // The census canonical strips the char (x123 Main St — no `\b` before the house number), but the
        // bounded RAW pass still sees the boundary, so the STREET shape withholds it.
        assertEquals(TextSlot.WITHHELD, slot("x\uDB40\uDC20123 Main St"))
        assertEquals(TextSlot.WITHHELD, slot("x\uDB40\uDC20Jane S is here"))
    }

    @Test
    fun `AJ1 - an id-shaped uid is judged by the id path first`() {
        fun uid(value: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", uniqueId = value, text = "Continue"),
            null, meta, platform, day,
        )!!.root.text.getValue("uid")
        assertEquals(TextSlot.WITHHELD, uid("chip_Riley_S"))
        assertEquals(TextSlot.WITHHELD, uid("deliver_to_Sam"))
        val chrome = uid("offerAcceptCta")
        assertTrue("$chrome", chrome.h != null && chrome.kind.startsWith("words:"))
        assertEquals(CensusHash.of("offerAcceptCta"), chrome.h)
    }

    @Test
    fun `AJ2 - a value that canonicalizes to nothing is dropped, never a phantom slot`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.TextView", text = "\u200B", contentDescription = "Continue"),
            null, meta, platform, day,
        )!!.root.text
        assertEquals(setOf("desc"), out.keys)
    }

    @Test
    fun `AK1 - a uid is judged on its canonical form`() {
        fun uid(value: String) = SkeletonBuilder.build(
            UiNode(className = "android.widget.Button", uniqueId = value, text = "Continue"),
            null, meta, platform, day,
        )!!.root.text.getValue("uid")
        assertEquals(TextSlot.WITHHELD, uid("chip\uFF3FRiley\uFF3FS"))
        assertEquals(TextSlot.WITHHELD, uid("chip\u200BRiley\u200BS"))
    }

    @Test
    fun `AK2 - a uid PII hit seeds its duplicates across fields and nodes`() {
        val out = SkeletonBuilder.build(
            UiNode(className = "android.widget.LinearLayout", children = listOf(
                UiNode(className = "android.widget.Button", uniqueId = "chip_Riley_S", text = "chip_Riley_S"),
                UiNode(className = "android.widget.TextView", text = "chip_Riley_S"),
            )),
            null, meta, platform, day,
        )!!.root.children
        assertEquals(TextSlot.WITHHELD, out[0].text.getValue("uid"))
        assertEquals(TextSlot.WITHHELD, out[0].text.getValue("text"))
        assertEquals(TextSlot.WITHHELD, out[1].text.getValue("text"))
    }

    @Test
    fun `AK3 - an over-cap uid never reaches the id predicates`() {
        var calls = 0
        val filter = FrameFilter(
            judge = SkeletonBuilder::withholdingStep,
            idShapedPii = { calls++; IdPathJudgement.namePartCarriesPii(it) },
        )
        val root = filter.emit(filter.scan(UiNode(className = "android.widget.Button", uniqueId = "x_".repeat(2048), text = "Continue")))
        assertEquals(TextSlot.WITHHELD, root.text.getValue("uid"))
        assertEquals(0, calls)
        // A short uid does reach them.
        filter.scan(UiNode(className = "android.widget.Button", uniqueId = "offerAcceptCta"))
        assertEquals(1, calls)
    }
}
