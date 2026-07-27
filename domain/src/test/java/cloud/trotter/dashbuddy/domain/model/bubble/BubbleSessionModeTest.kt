package cloud.trotter.dashbuddy.domain.model.bubble

import org.junit.Assert.assertEquals
import org.junit.Test

/** #867 — the stored mode decodes fail-closed to the shipped presentation. */
class BubbleSessionModeTest {

    @Test
    fun `every mode round-trips through its wire value`() {
        BubbleSessionMode.entries.forEach { mode ->
            assertEquals(mode, BubbleSessionMode.fromWire(mode.wire))
        }
    }

    @Test
    fun `an absent value falls back to follow-active`() {
        assertEquals(BubbleSessionMode.FOLLOW_ACTIVE, BubbleSessionMode.fromWire(null))
        assertEquals(BubbleSessionMode.Default, BubbleSessionMode.fromWire(null))
    }

    @Test
    fun `an unknown or garbage value falls back to follow-active`() {
        listOf("", "  ", "MERGED_STACK", "follow", "pinned-to-uber", "42").forEach { wire ->
            assertEquals(wire, BubbleSessionMode.Default, BubbleSessionMode.fromWire(wire))
        }
    }

    @Test
    fun `decode is case-insensitive so a hand-edited preference still resolves`() {
        assertEquals(BubbleSessionMode.MERGED, BubbleSessionMode.fromWire("MERGED"))
        assertEquals(BubbleSessionMode.PINNED, BubbleSessionMode.fromWire("Pinned"))
    }
}
