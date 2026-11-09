package cloud.trotter.log.gig.feature.settings

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import cloud.trotter.log.gig.core.data.analytics.CsvExporter
import cloud.trotter.log.gig.core.data.analytics.LedgerRepository
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.FuelRecord
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.log.LogRepository
import cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicle
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicleKind
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicleStamp
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.RuleCapability
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Locale

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
        dashVehicleRepository = mock(),
        garageRepository = mock(),
        capabilityGrants = mock(),
        eventReceipt = mock(),
        context = mock(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `export forwards ledger lists folded stamps and all garage vehicles and writes five files`() = assertCsvExport(readable = true)

    @Test
    fun `export uses an empty vehicle list when the garage is unreadable`() = assertCsvExport(readable = false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertCsvExport(readable: Boolean) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val analytics: AnalyticsRepository = mock()
            val ledger: LedgerRepository = mock()
            val dashVehicles: DashVehicleRepository = mock()
            val garage: GarageRepository = mock()
            val context: Context = mock()
            val resolver: ContentResolver = mock()
            val tree: Uri = mock()
            val directory: Uri = mock()
            val expenses = listOf(ExpenseRecord(11, "expense-dash", 100, ExpenseCategory.PARKING, 4.5, "meter", null))
            val fuel = listOf(FuelRecord(22, null, 200, 30.0, 10.0, 1000.0, 300))
            val stamps = listOf(
                DashVehicleStamp("dash", "retired", 120.0, 200, 2, DashVehicleKind.SWAP),
                DashVehicleStamp("dash", "active", 100.0, 100, 1, DashVehicleKind.START),
                DashVehicleStamp("unknown", null, null, 300, 3, DashVehicleKind.START),
            )
            val folded = listOf(DashVehicle("dash", "retired", 100, 100.0), DashVehicle("unknown", null, 300, null))
            val vehicles = listOf(Vehicle("active", "Current car", 0), Vehicle("retired", "Old car", 0, retiredAt = 1))
            val exportedVehicles = if (readable) vehicles else emptyList()
            val bundle = CsvExporter.Bundle("deliveries", "sessions", "summary", "expenses", "fuel")
            whenever(ledger.allExpenses()).thenReturn(expenses)
            whenever(ledger.allFuel()).thenReturn(fuel)
            whenever(dashVehicles.stamps).thenReturn(flowOf(stamps))
            whenever(garage.garage).thenReturn(flowOf(Garage(vehicles, "active", readable)))
            whenever(analytics.buildCsvExport(any(), any(), any(), any(), eq(expenses), eq(fuel), eq(folded), eq(exportedVehicles)))
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
                val vm = DataExportViewModel(analytics, ledger, mock(), dashVehicles, garage, mock(), mock(), context, dispatcher)
                vm.export(tree)
                runCurrent()
                verify(ledger).allExpenses()
                verify(ledger).allFuel()
                verify(analytics).buildCsvExport(
                    eq(ZoneId.systemDefault()), any(), eq(Long.MIN_VALUE), eq(Long.MAX_VALUE), eq(expenses), eq(fuel),
                    eq(folded), eq(exportedVehicles),
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
            receipts = "# Consent receipts: each choice, its date, the app version and the privacy notice version.\n",
        )
        assertTrue(out.contains("the app's main events, with known private details removed"))
        assertTrue("header must state the scrub count", out.contains("Private details were removed from 3 line(s)."))
        assertTrue(out.contains("offer received"))
        assertTrue("the receipts follow the log body", out.indexOf("# Consent receipts") > out.indexOf("offer received"))
    }

    @Test
    fun `buildLogFile emits a header-only file when the log is empty`() {
        val out = viewModel().buildLogFile(body = "", scrubbedLines = 0, receipts = consentReceiptsSection(emptyList()) { it })
        assertTrue(out.contains("the app's main events, with known private details removed"))
        assertTrue(out.contains("Private details were removed from 0 line(s)."))
        // No log body — header lines only, all commented.
        val nonCommentLines = out.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(emptyList<String>(), nonCommentLines)
    }

    private val labels = ReceiptLabels(
        allowed = "Allowed", denied = "Not allowed", format = "%1${'$'}s, %2${'$'}s, app %3${'$'}s, privacy notice %4${'$'}d",
        screenEvents = "Screen events",
    )
    private val zone = ZoneId.of("UTC")

    /** #170 — every stored receipt is listed: Screen events first, then capabilities by key, named where enumerated. */
    @Test
    fun `receipt lines list the Screen events receipt and each capability receipt`() {
        val day = 1_791_244_800_000L // 2026-10-06 UTC
        val capability = RuleCapability(
            ruleId = "doordash.screen.offer", action = RuleAction.CONFIRM_DECLINE, targetBindName = "declineButton",
            key = "b-key", source = "asset:rules/doordash.json",
        )
        val lines = consentReceiptLines(
            eventReceipt = ConsentReceipt(day, "0.230.1+abc", 16, true),
            receipts = mapOf(
                "b-key" to ConsentReceipt(day, "0.230.1+abc", 16, false),
                "a-retired" to ConsentReceipt(day, "0.229.0+def", 15, true),
            ),
            capabilities = listOf(capability),
            labels = labels,
            zone = zone,
            locale = Locale.US,
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Screen events: Allowed, "))
        assertTrue(lines[0], lines[0].endsWith(", app 0.230.1+abc, privacy notice 16"))
        assertTrue(lines[1], lines[1].startsWith("Capability a-retired: Allowed, "))
        assertTrue(lines[1], lines[1].endsWith("privacy notice 15"))
        assertTrue(lines[2], lines[2].startsWith("confirm_decline on doordash.screen.offer (b-key): Not allowed, "))
    }

    /** #170 — the appended lines pass the sink's scrubber; a failed scrubber drops the section, never writes it raw. */
    @Test
    fun `the receipts section is scrubbed and dropped when the scrubber fails`() {
        val lines = listOf("Screen events: Allowed, x", "Capability k: Allowed, y")

        val scrubbed = consentReceiptsSection(lines) { input -> input.map { if ("k:" in it) "[scrubbed:marker]" else it } }
        assertTrue(scrubbed.contains("Screen events: Allowed, x\n"))
        assertTrue(scrubbed.contains("[scrubbed:marker]\n"))
        assertFalse(scrubbed.contains("Capability k"))

        val failed = consentReceiptsSection(lines) { null }
        assertTrue(failed.contains("# Left out: the privacy filter failed."))
        lines.forEach { assertFalse("never raw: $it", failed.contains(it)) }

        assertTrue(consentReceiptsSection(emptyList()) { error("not called") }.contains("# No choices recorded."))
    }
}
