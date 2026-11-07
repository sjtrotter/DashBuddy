package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule behind the hub's correction dialogs and the range picker's earning dots. */
class AnalyticsRecordsGatesTest {

    private val ready = AnalyticsUiState()
    private val days = PickerMonthDays()

    @Test fun `figures show only when loaded, read and not rebuilding`() {
        assertTrue(ready.figuresShown())
        assertTrue(ready.copy(records = RecordsStatus.UPDATING).figuresShown())
        assertFalse(ready.copy(loaded = false).figuresShown())
        assertFalse(ready.copy(failed = true).figuresShown())
        assertFalse(ready.copy(records = RecordsStatus.REBUILDING).figuresShown())
        assertFalse(ready.copy(failed = true, records = RecordsStatus.REBUILDING).figuresShown())
    }

    @Test fun `picker marks hold back before a failed month read can show`() {
        assertEquals(PickerMarks.SHOWN, pickerMarks(ready, days))
        assertEquals(PickerMarks.FAILED, pickerMarks(ready, days.copy(failed = true)))
        assertEquals(PickerMarks.HELD_BACK, pickerMarks(ready.copy(records = RecordsStatus.REBUILDING), days))
        assertEquals(PickerMarks.HELD_BACK, pickerMarks(ready.copy(failed = true), days))
        assertEquals(PickerMarks.HELD_BACK, pickerMarks(ready.copy(loaded = false), days.copy(failed = true)))
    }
}
