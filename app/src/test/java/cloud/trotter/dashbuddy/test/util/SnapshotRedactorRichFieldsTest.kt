package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1147 — the corpus intake scrubber iterates the [UiNodeTextField] wire SSOT, so the TalkBack-study
 * keys (`pane`, `role`, `hint`, `tooltip`, `error`, `clickLabel`, `uid`) are scrubbed on the commit
 * path with NO edit to [SnapshotRedactor]. This pins that: a customer name riding only one of them
 * is redacted exactly as it would be in `text`.
 */
class SnapshotRedactorRichFieldsTest {

    @Test
    fun `a customer lead-in in the pane title is redacted`() {
        val out = SnapshotRedactor.redact("""{"pane":"Deliver to Jane D","class":"android.view.View"}""")
        assertFalse(out, out.contains("Jane D"))
        assertTrue(out, out.contains("\"pane\":\"Deliver to "))
    }

    @Test
    fun `every new wire key is scrubbed like text`() {
        val newKeys = UiNodeTextField.entries.map { it.wire } - setOf("text", "desc", "state")
        assertTrue("the #1147 keys are in the SSOT", newKeys.containsAll(listOf("pane", "role", "hint", "tooltip", "error", "clickLabel", "uid")))
        for (key in newKeys) {
            val out = SnapshotRedactor.redact("""{"$key":"Deliver to Jane D"}""")
            assertFalse("$key must be scrubbed: $out", out.contains("Jane D"))
        }
    }
}
