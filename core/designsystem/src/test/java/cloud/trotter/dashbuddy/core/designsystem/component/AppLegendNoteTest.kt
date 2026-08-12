package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the legend's three-way note rule (#1024 review F5).
 *
 * This exists because the Money tab de-duplicates ONE segment's dollar figure — the kept money the
 * recap hero already states — and the difference between "state nothing" and "we have no figure"
 * is the difference between a blank slot and a **percentage share**, which that card's copy rules
 * forbid. Before this, the distinction was carried by a `""` sentinel that any `isNullOrBlank()`
 * tidy-up would have collapsed into the percentage path, silently. [legendNote] is the pure
 * decision so the rule can be asserted without composing anything.
 */
class AppLegendNoteTest {

    private fun segment(
        value: Float,
        note: String? = null,
        noteHidden: Boolean = false,
    ) = AppSegment(label = "Stayed with you", value = value, color = Color.Green, note = note, noteHidden = noteHidden)

    @Test
    fun `a stated note is printed verbatim`() {
        assertEquals("$296.10", legendNote(segment(296.1f, note = "$296.10"), total = 412.83f))
    }

    @Test
    fun `no note falls back to the computed percentage share`() {
        assertEquals("50%", legendNote(segment(50f), total = 100f))
    }

    /** The #1024 case: the figure is stated elsewhere on the screen, so this slot must stay EMPTY. */
    @Test
    fun `a hidden note prints nothing — never a percentage`() {
        assertNull(legendNote(segment(296.1f, noteHidden = true), total = 412.83f))
    }

    /** Hiding wins over a supplied note: the flag is the caller's explicit "do not state this here". */
    @Test
    fun `hidden wins over a supplied note`() {
        assertNull(legendNote(segment(296.1f, note = "$296.10", noteHidden = true), total = 412.83f))
    }

    /** A blank note is a STATED empty string, not the hidden flag — the two must not be conflated. */
    @Test
    fun `a blank note is not the hidden flag`() {
        assertEquals("", legendNote(segment(50f, note = ""), total = 100f))
    }

    /**
     * An all-zero legend (an empty window) has no denominator; the percentage degrades to 0% rather
     * than dividing by zero into a NaN the renderer would print.
     */
    @Test
    fun `a non-positive total degrades the percentage to zero`() {
        assertEquals("0%", legendNote(segment(0f), total = 0f))
        assertEquals("0%", legendNote(segment(10f), total = -5f))
    }
}
