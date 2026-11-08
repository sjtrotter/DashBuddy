package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one rule behind Recap's figures and its assign dialog. */
class AnalyticsRecordsGatesTest {

    private val ready = AnalyticsUiState()

    @Test fun `figures show only when loaded, read and not rebuilding`() {
        assertTrue(ready.figuresShown())
        assertTrue(ready.copy(records = RecordsStatus.UPDATING).figuresShown())
        assertFalse(ready.copy(loaded = false).figuresShown())
        assertFalse(ready.copy(failed = true).figuresShown())
        assertFalse(ready.copy(records = RecordsStatus.REBUILDING).figuresShown())
        assertFalse(ready.copy(failed = true, records = RecordsStatus.REBUILDING).figuresShown())
    }
}
