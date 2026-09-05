package cloud.trotter.dashbuddy.test.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1064 — the intake scrubber's `"Return "` lead-in.
 *
 * #994 added it to [SnapshotRedactor.NAME_PREFIXES] unconditionally, for the timeline's
 * return-order task line (`"Return <FirstName L> to <store>"`). But DoorDash renders the same
 * word as its own `"Return to dash"` button, which is a `hasText` **recognition anchor** on four
 * rules — so intake masked it to `"Return [redacted]"`, the frame re-classified as
 * `side_nav_drawer`, and the 09-05 intake's two `on_dash_map` fixtures failed the golden guard and
 * were set aside. The intake list's stated asymmetry ("an over-scrub here only costs triage text")
 * is false when the scrubbed token IS an anchor.
 *
 * The prefix now lives in [SnapshotRedactor.GATED_NAME_PREFIXES] with a predicate over its tail,
 * and the predicate reuses [SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN] — the byte-SSOT the rule
 * side shares — rather than a second copy of the name shape.
 */
class SnapshotRedactorNamePrefixTest {

    /** Route a raw text value through the redactor exactly as an id-less fixture node would. */
    private fun scrubText(raw: String): String {
        val out = SnapshotRedactor.redact("""{"text":"${raw.replace("\"", "\\\"")}"}""")
        return Regex("""^\{"text":"(.*)"}$""").find(out)!!.groupValues[1]
    }

    // --- the chrome must survive -------------------------------------------

    @Test
    fun `Return to dash chrome is untouched`() {
        for (raw in listOf("Return to dash", "Return to Dash", "Return to dashing")) {
            assertEquals("'$raw' is DoorDash chrome and a rule anchor", raw, scrubText(raw))
            assertNull("'$raw' is not a customer lead-in", SnapshotRedactor.customerLeadIn(raw))
        }
    }

    /**
     * The real receipt: every committed `on_dash_map` fixture carries the `"Return to dash"`
     * button. Re-running intake over them must be a byte no-op on that value — this is the exact
     * regression that set the 09-05 captures aside.
     */
    @Test
    fun `re-running intake over the committed on_dash_map corpus keeps the Return to dash anchor`() {
        val files = File("src/test/resources/snapshots/on_dash_map")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .filter { it.readText().contains("Return to dash") }
        assertTrue("the on_dash_map corpus must still carry the anchor", files.isNotEmpty())
        for (file in files) {
            val out = SnapshotRedactor.redact(file.readText())
            assertTrue(
                "${file.name}: 'Return to dash' must survive intake redaction",
                out.contains("\"Return to dash\""),
            )
            assertFalse(
                "${file.name}: the anchor must not be masked",
                out.contains("Return ${SnapshotRedactor.MASK}"),
            )
        }
    }

    // --- the customer task line must still be scrubbed ----------------------

    @Test
    fun `Return name to store is still scrubbed, keeping only the lead-in`() {
        for (raw in listOf(
            "Return Sample T. to Store",
            "Return Riley P to H-E-B",
            "Return Mary-Jo K to Sample Grocery Co",
            "Return José R. to Sprouts Farmers Market #118",
        )) {
            assertEquals("'$raw' carries a customer name", "Return " + SnapshotRedactor.MASK, scrubText(raw))
            assertEquals("Return ", SnapshotRedactor.customerLeadIn(raw))
        }
    }

    /** A return line with no store tail is still a name — the gate tests the whole tail then. */
    @Test
    fun `Return name with no store tail is still scrubbed`() {
        assertEquals("Return " + SnapshotRedactor.MASK, scrubText("Return Sample T."))
    }

    /**
     * Byte-identical to the pre-#1064 behaviour on the committed `timeline/` corpus: the one
     * fixture carrying the return conjugation (a [CorpusDecoys] pseudonym, deliberately raw)
     * still redacts to exactly `"Return [redacted]"` and nothing else on that value moves.
     */
    @Test
    fun `the committed timeline return conjugation redacts exactly as before`() {
        val decoy = "Return Riley P to H-E-B"
        assertTrue("the decoy must still be enumerated", CorpusDecoys.isDecoy(decoy))
        val files = File("src/test/resources/snapshots/timeline")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .filter { it.readText().contains(decoy) }
        assertEquals("exactly one committed timeline fixture carries the return line", 1, files.size)
        val file = files.single()
        val content = file.readText()
        assertEquals(
            "${file.name}: the ONLY change intake makes is masking the return line's tail",
            content.replace(decoy, "Return " + SnapshotRedactor.MASK),
            SnapshotRedactor.redact(content),
        )
    }

    // --- the ungated lead-ins are unchanged ---------------------------------

    @Test
    fun `the unconditional lead-ins still scrub their whole tail`() {
        for (prefix in SnapshotRedactor.NAME_PREFIXES) {
            val raw = prefix + "Sample T."
            assertEquals("'$raw'", prefix + SnapshotRedactor.MASK, scrubText(raw))
            assertEquals(prefix, SnapshotRedactor.customerLeadIn(raw))
        }
        assertFalse(
            "'Return ' must no longer be an unconditional lead-in",
            SnapshotRedactor.NAME_PREFIXES.contains("Return "),
        )
        assertTrue(
            "'Return ' must be a gated lead-in",
            SnapshotRedactor.GATED_NAME_PREFIXES.containsKey("Return "),
        )
    }

    @Test
    fun `a bare prefix with no tail is not a lead-in`() {
        for (prefix in SnapshotRedactor.NAME_PREFIXES + SnapshotRedactor.GATED_NAME_PREFIXES.keys) {
            assertNull("'$prefix' alone carries no customer", SnapshotRedactor.customerLeadIn(prefix))
        }
    }
}
