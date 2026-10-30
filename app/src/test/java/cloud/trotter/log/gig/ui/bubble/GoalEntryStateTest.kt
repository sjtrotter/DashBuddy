package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class GoalEntryStateTest {
    @Test fun `show back and dismiss control visibility`() {
        val entry = GoalEntryState()
        assertFalse(entry.open)
        entry.show(); assertTrue(entry.open)
        entry.back(); assertFalse(entry.open)
        entry.show(); entry.dismiss(); assertFalse(entry.open)
    }

    @Test fun `show on an open entry preserves its pending completion identity`() {
        val entry = GoalEntryState()
        assertTrue(entry.show())
        val id = entry.entryId
        assertFalse(entry.show())
        assertEquals(id, entry.entryId)
        entry.saved(id)
        assertFalse(entry.open)
    }

    @Test fun `open and pending completion identity are saved and restored`() {
        for (wasOpen in listOf(false, true)) {
            val entry = GoalEntryState().apply { show(); if (!wasOpen) dismiss() }
            val saved = with(GoalEntryState.Saver) {
                object : SaverScope { override fun canBeSaved(value: Any) = true }.save(entry)
            }!!
            val restored = GoalEntryState.Saver.restore(saved)!!
            assertEquals(wasOpen, restored.open)
            assertEquals(entry.entryId, restored.entryId)
        }
    }

    @Test fun `completion closes its entry but not a reopened entry`() {
        val entry = GoalEntryState()
        entry.show()
        val oldId = entry.entryId
        entry.back(); entry.show()
        entry.saved(oldId); assertTrue(entry.open)
        entry.saved(entry.entryId); assertFalse(entry.open)
    }
}
