package cloud.trotter.dashbuddy.ui.main.settings

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #551 — the bug-report (shareable log) export assembles a header stating the auto-scrub count over
 * the shareable-sink body, and writes a header-only file when the log is empty (CSV empty-parity).
 * The SAF directory-write edge is untestable here (as with the CSV export), so this covers the pure
 * content assembly — the P7-relevant piece: the header is honest about scrubbing, the body is the
 * INFO+ shareable stream only.
 */
class DataExportViewModelTest {

    private fun viewModel() = DataExportViewModel(
        analyticsRepository = mock<AnalyticsRepository>(),
        logRepository = mock<LogRepository>(),
        context = mock(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `buildLogFile states the scrub count and appends the body`() {
        val out = viewModel().buildLogFile(
            body = "2026-01-01 00:00:00.000 [Idle] INFO/Milestone: offer received\n",
            scrubbedLines = 3,
        )
        assertTrue(out.contains("INFO+ milestones only"))
        assertTrue("header must state the scrub count", out.contains("3 line(s) were auto-scrubbed"))
        assertTrue(out.contains("offer received"))
    }

    @Test
    fun `buildLogFile emits a header-only file when the log is empty`() {
        val out = viewModel().buildLogFile(body = "", scrubbedLines = 0)
        assertTrue(out.contains("INFO+ milestones only"))
        assertTrue(out.contains("0 line(s) were auto-scrubbed"))
        // No log body — header lines only, all commented.
        val nonCommentLines = out.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(emptyList<String>(), nonCommentLines)
    }
}
