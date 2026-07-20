package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #803 — the id-less delivery-instructions-body blind class. A residence-entry
 * PIN ("pin 4821") embedded in a free-text instructions body reached a corpus
 * candidate past every layer. These pin down the two test-side controls this
 * issue adds: [SnapshotRedactor]'s `pin` keyword mask (scrubs a NEW inbound
 * fixture) and [SnapshotSecurityScanner]'s `pin` shape (fails the corpus gate on
 * recurrence).
 */
class InstructionsBodyPiiScrubTest {

    // --- SnapshotRedactor: the `pin <digits>` mask (digit-adjacent) -----------

    /** Route a raw text value through the redactor exactly as an id-less fixture node would. */
    private fun scrubText(raw: String): String =
        SnapshotRedactor.redact("""{"text":"${raw.replace("\"", "\\\"")}"}""")

    @Test
    fun `pin followed by digits is masked, keeping the lead-in`() {
        // Space, colon (spaced + fused), hash, fully fused (no separator), and mid-body.
        for (raw in listOf(
            "pin 4821", "PIN: 4821", "pin:4821", "PIN #4821", "Pin4821",
            "Your pin 4821 opens the gate",
        )) {
            val out = scrubText(raw)
            assertFalse("digits must not survive in '$raw' -> $out", out.contains("4821"))
            assertTrue("pin lead-in kept in '$raw' -> $out", out.contains(SnapshotRedactor.MASK))
        }
    }

    @Test
    fun `pin without adjacent digits is left untouched (no false positive)`() {
        // "PIN pad" / "pin it" / a word merely containing 'pin' must NOT be masked.
        for (raw in listOf("PIN pad", "pin it to the board", "Enter PIN code", "shopping list", "opinion")) {
            val out = scrubText(raw)
            assertFalse("'$raw' must not be masked -> $out", out.contains(SnapshotRedactor.MASK))
        }
    }

    // --- SnapshotSecurityScanner: the corpus-gate `pin <digits>` shape --------

    @Test
    fun `scanner flags a pin-code fragment as toxic`() {
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Hand it to customer"),
                UiNode(text = "Leave at door. pin 1234 to enter"), // the id-less instructions body
            ),
        ).restoreParents()
        val result = SnapshotSecurityScanner.scan(tree)
        assertTrue("a 'pin 1234' fragment must make the fixture toxic", result.isToxic)
        assertTrue(
            "trigger names the pin shape",
            result.triggers.any { it.second == "pin-code-shape" },
        )
    }

    @Test
    fun `scanner flags pin colon and fused variants and a bare gate code`() {
        for (body in listOf("PIN: 1234", "Pin1234", "gate 4821", "Gate: 4821")) {
            val tree = UiNode(text = body).restoreParents()
            assertTrue("'$body' must be flagged", SnapshotSecurityScanner.scan(tree).isToxic)
        }
    }

    @Test
    fun `scanner does not flag pin-adjacent non-PII text`() {
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Enter PIN code"),
                UiNode(text = "PIN code not provided"),
                UiNode(text = "Collect PIN from customer"),
            ),
        ).restoreParents()
        val result = SnapshotSecurityScanner.scan(tree)
        assertFalse("pin UI copy without adjacent digits must stay clean", result.isToxic)
    }

    @Test
    fun `scanner requires at least three digits`() {
        // Two digits after "pin" (unlikely to be a real code) stays clean; the
        // gate targets the 3+ digit residence-PIN shape.
        val tree = UiNode(text = "pin 42 stars").restoreParents()
        assertEquals(false, SnapshotSecurityScanner.scan(tree).isToxic)
    }
}
