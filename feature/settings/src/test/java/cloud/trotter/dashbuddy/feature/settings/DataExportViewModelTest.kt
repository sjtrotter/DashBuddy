package cloud.trotter.dashbuddy.feature.settings

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import cloud.trotter.dashbuddy.core.data.analytics.CsvExporter
import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.domain.analytics.ExpenseRecord
import cloud.trotter.dashbuddy.domain.analytics.FuelRecord
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.Mockito.mockStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/**
 * #551 — the bug-report (shareable log) export assembles a header stating the auto-scrub count over
 * the shareable-sink body, and writes a header-only file when the log is empty (CSV empty-parity).
 * The CSV test mocks the SAF platform edge and exercises export through all five writes, including
 * the distinct expense and fuel arguments forwarded to the analytics repository.
 */
class DataExportViewModelTest {

    private fun viewModel() = DataExportViewModel(
        analyticsRepository = mock<AnalyticsRepository>(),
        ledgerRepository = mock(),
        logRepository = mock<LogRepository>(),
        context = mock(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `export forwards both ledger lists and writes all five named files`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val analytics: AnalyticsRepository = mock()
            val ledger: LedgerRepository = mock()
            val context: Context = mock()
            val resolver: ContentResolver = mock()
            val tree: Uri = mock()
            val directory: Uri = mock()
            val expenses = listOf(ExpenseRecord(11, "expense-dash", 100, ExpenseCategory.PARKING, 4.5, "meter", null))
            val fuel = listOf(FuelRecord(22, null, 200, 30.0, 10.0, 1000.0, 300))
            val bundle = CsvExporter.Bundle("deliveries", "sessions", "summary", "expenses", "fuel")
            whenever(ledger.allExpenses()).thenReturn(expenses)
            whenever(ledger.allFuel()).thenReturn(fuel)
            whenever(analytics.buildCsvExport(any(), any(), any(), any(), eq(expenses), eq(fuel)))
                .thenReturn(bundle)
            whenever(context.contentResolver).thenReturn(resolver)
            val outputs = linkedMapOf<String, ByteArrayOutputStream>()
            val streams = mutableMapOf<Uri, ByteArrayOutputStream>()
            whenever(resolver.openOutputStream(any<Uri>())).thenAnswer { streams[it.getArgument<Uri>(0)] }
            mockStatic(DocumentsContract::class.java).use { documents ->
                documents.`when`<String> { DocumentsContract.getTreeDocumentId(tree) }.thenReturn("root")
                documents.`when`<Uri> { DocumentsContract.buildDocumentUriUsingTree(tree, "root") }.thenReturn(directory)
                documents.`when`<Uri> {
                    DocumentsContract.createDocument(eq(resolver), eq(directory), eq("text/csv"), any())
                }.thenAnswer { call ->
                    val name = call.getArgument<String>(3)
                    val uri: Uri = mock()
                    val output = ByteArrayOutputStream()
                    outputs[name] = output
                    streams[uri] = output
                    uri
                }
                val vm = DataExportViewModel(analytics, ledger, mock(), context, dispatcher)
                vm.export(tree)
                runCurrent()
                verify(ledger).allExpenses()
                verify(ledger).allFuel()
                verify(analytics).buildCsvExport(
                    eq(ZoneId.systemDefault()), any(), eq(Long.MIN_VALUE), eq(Long.MAX_VALUE), eq(expenses), eq(fuel),
                )
                assertEquals(DataExportViewModel.ExportState.Success(5), vm.state.value)
                assertEquals(
                    mapOf(
                        "deliveries.csv" to "deliveries", "sessions.csv" to "sessions",
                        "summary.csv" to "summary", "expenses.csv" to "expenses", "fuel.csv" to "fuel",
                    ),
                    outputs.mapValues { it.value.toString(Charsets.UTF_8.name()) },
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `buildLogFile states the scrub count and appends the body`() {
        val out = viewModel().buildLogFile(
            body = "2026-01-01 00:00:00.000 [Idle] INFO/Milestone: offer received\n",
            scrubbedLines = 3,
        )
        assertTrue(out.contains("INFO+ milestones; known sensitive text filtered"))
        assertTrue("header must state the scrub count", out.contains("3 line(s) were auto-scrubbed"))
        assertTrue(out.contains("offer received"))
    }

    @Test
    fun `buildLogFile emits a header-only file when the log is empty`() {
        val out = viewModel().buildLogFile(body = "", scrubbedLines = 0)
        assertTrue(out.contains("INFO+ milestones; known sensitive text filtered"))
        assertTrue(out.contains("0 line(s) were auto-scrubbed"))
        // No log body — header lines only, all commented.
        val nonCommentLines = out.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(emptyList<String>(), nonCommentLines)
    }
}
