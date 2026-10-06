package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1145 / ADR-0011 §2 — the byte-SSOT pins for the `PiiShapes` promotion.
 *
 * Three properties:
 *  1. **Delegation, not a copy.** Every `SnapshotRedactor` shape constant IS the `PiiShapes` object
 *     (`assertSame`), so the census filter and the corpus intake cannot drift.
 *  2. **The move changed no byte.** Each pattern's text AND options equal the pre-#1145 source bytes,
 *     pinned here verbatim; the anchored name pattern is still byte-identical to the rule side's.
 *  3. **The derived name variants share ONE body**, and every mask the commit path emits is covered by
 *     `PiiShapes.containsMask`.
 */
class PiiShapesParityTest {

    private companion object {
        // Pre-#1145 bytes, copied verbatim from SnapshotRedactor at 6fcf2b22 (the promotion must not move a byte).
        private val ORIGINAL_PATTERNS: Map<String, Pair<String, Set<RegexOption>>> = mapOf(
            "PHONE" to ("""\b\d{3}[-.\s]?\d{3}[-.\s]?\d{4}\b""" to emptySet()),
            "EMAIL" to ("""\b[\w.+-]+@[\w-]+\.[\w.-]+\b""" to emptySet()),
            "STREET" to ("""\b\d{1,6}\s+([A-Za-z0-9.'\-]+\s+){0,4}""" +
            """(Rd|Road|St|Street|Ave|Avenue|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|""" +
            """Hwy|Highway|Pkwy|Parkway|Pl|Place|Cir|Circle|Trl|Trail|Loop|Ter|Terrace|Pike|""" +
            """Path|Walk|Pass|Run|Row|Bend|Bnd|Cove|Cv|Crossing|Xing|Square|Sq|Plaza|Plz|""" +
            """Point|Pt|Alley|Aly|Trace|Trce|Manor|Mnr|Grove|Grv|Ridge|Rdg|Creek|Crk|Hill|Hills|""" +
            """I-\d+|FM\s*\d+|US-?\d+)\b""" to setOf(RegexOption.IGNORE_CASE)),
            "CITY_STATE_ZIP" to ("""\b[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+){0,3},\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""" to emptySet()),
            "FULL_ADDRESS" to ("""\b\d{1,6}\s+[^,]+,\s*[A-Z][A-Za-z .'-]+,\s*[A-Z]{2}\s+\d{5}(?:-\d{4})?\b""" to emptySet()),
            "BARE_STREET" to ("""^\d{2,6}\s+[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z0-9.'-]+){0,3}$""" to emptySet()),
            "APT" to ("""(?i)\b(apt|suite|ste|unit|bldg|building|gate code|gate)\b[:#\s]*[A-Za-z0-9\-]+""" to emptySet()),
            "PIN" to ("""(?i)\b(pin)[\s:#]*\d{3,}""" to emptySet()),
            "QUOTED_NOTE" to (""""[^"]{6,}"""" to emptySet()),
            "CARD" to ("""(?i)\b(visa|mastercard|amex|american express|discover|debit card)\b""" +
            """[\s ]*[•·•*xX.]{2,}[\s ]*\d{2,4}""" to emptySet()),
        )

        const val ORIGINAL_FIRST_LAST_INITIAL_PATTERN =
            "^\\s{0,8}[\\p{L}][\\p{L}'-]{0,20}(\\s{1,4}[\\p{L}][\\p{L}'-]{0,20}){0,3}\\s{1,4}[A-Z]\\.?\\s{0,8}$"

        val ORIGINAL_PII_ID_SUFFIXES: Set<String> = setOf(
            "user_name",
            "customer_name",
            "address_line_1",
            "address_line_2",
            "address_subpremise_line",
            // #1079: the in-transit nav sheet's `bottom_sheet_` twin joined the table (fable review of PR #1245).
            "bottom_sheet_subpremise_line",
            "primaryManeuverText",
            "subManeuverText",
            "secondaryManeuverText",
            "roadNameView",
            "arriving_at_title",
            "message_self_message",
            "message_other_message",
            "message_input",
            "chat_input_text_field",
            "bottom_sheet_address_line_1",
            "bottom_sheet_address_line_2",
            "tvTitle",
            "tvLastMessage",
            "bottom_sheet_instructions",
            "step_description",
            "instructions_list",
            "instruction_text",
            "dasher_instruction_content_collapsed",
            "order_cx_name",
            // #1160 review UU3 — DELIBERATELY added after the promotion (the intake list must hold every
            // runtime ID_MARKER_TABLE suffix): the two instruction-body ids.
            "description_text_view",
            "dasher_instruction_content_expanded",
        )

        val ORIGINAL_NAME_PREFIXES: List<String> = listOf(
            "Pickup for ",
            "Pickup from ",
            "Deliver to ",
            "Delivery for ",
            "Order for ",
            "Message from ",
            "Heading to ",
            "Pick up at ",
        )
    }

    @Test
    fun `every SnapshotRedactor shape delegates to the PiiShapes object`() {
        // #1160 review AL3 (deliberate): the intake id list is DERIVED from the runtime table, not PiiShapes.
        assertSame(CustomerTextMarkers.ID_MARKER_SUFFIXES, SnapshotRedactor.PII_ID_SUFFIXES)
        assertSame(PiiShapes.NAME_PREFIXES, SnapshotRedactor.NAME_PREFIXES)
        assertSame(PiiShapes.GATED_NAME_PREFIXES, SnapshotRedactor.GATED_NAME_PREFIXES)
        assertSame(PiiShapes.PHONE, SnapshotRedactor.PHONE)
        assertSame(PiiShapes.EMAIL, SnapshotRedactor.EMAIL)
        assertSame(PiiShapes.STREET, SnapshotRedactor.STREET)
        assertSame(PiiShapes.CITY_STATE_ZIP, SnapshotRedactor.CITY_STATE_ZIP)
        assertSame(PiiShapes.FULL_ADDRESS, SnapshotRedactor.FULL_ADDRESS)
        assertSame(PiiShapes.BARE_STREET, SnapshotRedactor.BARE_STREET)
        assertSame(PiiShapes.FIRST_LAST_INITIAL, SnapshotRedactor.FIRST_LAST_INITIAL)
        assertSame(PiiShapes.APT, SnapshotRedactor.APT)
        assertSame(PiiShapes.PIN, SnapshotRedactor.PIN)
        assertSame(PiiShapes.QUOTED_NOTE, SnapshotRedactor.QUOTED_NOTE)
        assertSame(PiiShapes.CARD, SnapshotRedactor.CARD)
        assertEquals(PiiShapes.FIRST_LAST_INITIAL_PATTERN, SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN)
        listOf("Pickup for Sam", "Return Riley P to H-E-B", "Return to dash", "Accept").forEach {
            assertEquals(it, PiiShapes.customerLeadIn(it), SnapshotRedactor.customerLeadIn(it))
        }
    }

    @Test
    fun `the promoted patterns are byte-identical to the pre-promotion source, options included`() {
        val live = mapOf(
            "PHONE" to PiiShapes.PHONE, "EMAIL" to PiiShapes.EMAIL, "STREET" to PiiShapes.STREET,
            "CITY_STATE_ZIP" to PiiShapes.CITY_STATE_ZIP, "FULL_ADDRESS" to PiiShapes.FULL_ADDRESS,
            "BARE_STREET" to PiiShapes.BARE_STREET, "APT" to PiiShapes.APT, "PIN" to PiiShapes.PIN,
            "QUOTED_NOTE" to PiiShapes.QUOTED_NOTE, "CARD" to PiiShapes.CARD,
        )
        assertEquals(ORIGINAL_PATTERNS.keys, live.keys)
        ORIGINAL_PATTERNS.forEach { (name, pin) ->
            val regex = live.getValue(name)
            assertEquals("$name pattern bytes", pin.first, regex.pattern)
            // Compare against a regex compiled from the pinned bytes + pinned constructor options: Kotlin
            // folds an inline `(?i)` into `options`, so the pinned set alone is not the observable value.
            assertEquals("$name options", Regex(pin.first, pin.second).options, regex.options)
        }
        assertEquals(ORIGINAL_FIRST_LAST_INITIAL_PATTERN, PiiShapes.FIRST_LAST_INITIAL_PATTERN)
        assertEquals(setOf(RegexOption.IGNORE_CASE), PiiShapes.FIRST_LAST_INITIAL.options)
        // AL3 (deliberate): the same SET of suffixes, now the table's rows; only the match widened (endsWith).
        assertEquals(ORIGINAL_PII_ID_SUFFIXES, CustomerTextMarkers.ID_MARKER_SUFFIXES) // pre-#1145 + the UU3 additions
        assertEquals(ORIGINAL_NAME_PREFIXES, PiiShapes.NAME_PREFIXES)
        // #1127 added the gated `Contact ` lead-in (tail = the whole name shape; 'Contact support' /
        // 'Contact Customer' fail the tail and stay chrome).
        assertEquals(setOf("Return ", "Contact "), PiiShapes.GATED_NAME_PREFIXES.keys)
        assertEquals("Contact ", PiiShapes.customerLeadIn("Contact Jane L"))
        for (chrome in listOf("Contact support", "Contact Customer", "Contact")) {
            assertEquals(chrome, null, PiiShapes.customerLeadIn(chrome))
        }
    }

    @Test
    fun `both name variants derive from the one body`() {
        val body = PiiShapes.FIRST_LAST_INITIAL_BODY
        assertEquals("^\\s{0,8}" + body + "\\s{0,8}$", PiiShapes.FIRST_LAST_INITIAL_PATTERN)
        val tokens = PiiShapes.FIRST_LAST_INITIAL_TOKENS
        assertEquals(tokens + "[A-Z]\\.?", body)
        // Review AA3: the embedded variant shares the tokens verbatim; only its INITIAL is case-sensitive.
        assertEquals("(?<![\\p{L}])" + tokens + "(?-i:[A-Z])\\.?" + "(?![\\p{L}])", PiiShapes.FIRST_LAST_INITIAL_EMBEDDED)
        listOf("Take a photo", "Report a problem", "Start a Dash").forEach {
            assertTrue(it, !PiiShapes.hasNameShape(it))
        }
        assertTrue(PiiShapes.hasNameShape("jordan t"))
        assertEquals(setOf(RegexOption.IGNORE_CASE), PiiShapes.FIRST_LAST_INITIAL_EMBEDDED_REGEX.options)
        // The anchored variant misses an embedded name; the embedded one catches it (ADR §2 step 7).
        val sentence = "Jane S is waiting at the door"
        assertTrue(!PiiShapes.FIRST_LAST_INITIAL.containsMatchIn(sentence))
        assertTrue(PiiShapes.FIRST_LAST_INITIAL_EMBEDDED_REGEX.containsMatchIn(sentence))
        // Everything the anchored variant matches, the embedded one finds too.
        listOf("Brandon C", "José R", "O'Brien M", "Mary-Jo K.", "Firstname  L.").forEach {
            assertTrue(it, PiiShapes.FIRST_LAST_INITIAL.matches(it))
            assertTrue(it, PiiShapes.FIRST_LAST_INITIAL_EMBEDDED_REGEX.containsMatchIn(it))
        }
    }

    @Test
    fun `VALUE_SHAPES is every promoted value shape, in the redactor's match mode`() {
        val modes = PiiShapes.VALUE_SHAPES.associate { it.name to it.mode }
        assertEquals(ORIGINAL_PATTERNS.keys, modes.keys)
        modes.forEach { (name, mode) ->
            val expected = if (name == "BARE_STREET") PiiShapes.MatchMode.WHOLE_VALUE else PiiShapes.MatchMode.SUBSTRING
            assertEquals(name, expected, mode)
        }
    }

    @Test
    fun `every value the commit path rewrites carries a mask containsMask recognises`() {
        listOf(
            "Call 210-555-0100 now", "Email sam@example.com", "1425 Sample Ridge Dr", "San Antonio, TX 78254",
            "7610 Fletchers", "Apt 12", "pin 4821", "\"leave it by the door\"", "Visa ••••6222",
            "Brandon C", "Pickup for Sam", "Return Riley P to H-E-B",
        ).forEach { value ->
            val json = buildJsonObject { put("text", value) }.toString()
            val out = SnapshotRedactor.redact(json)
            assertTrue("the redactor must rewrite '$value'", out != json)
            assertTrue("'$out' must carry a recognised mask", PiiShapes.containsMask(out))
        }
        // The id path: a PII id masks the whole value to MASK.
        val byId = SnapshotRedactor.redact(buildJsonObject { put("id", "x:id/customer_name"); put("text", "Sam") }.toString())
        assertTrue(PiiShapes.containsMask(byId))
        assertTrue(PiiShapes.containsMask(SnapshotRedactor.MASK))
    }
}
