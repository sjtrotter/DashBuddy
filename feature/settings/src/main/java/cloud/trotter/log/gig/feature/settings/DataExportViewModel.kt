package cloud.trotter.log.gig.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CsvExporter
import cloud.trotter.log.gig.core.data.analytics.LedgerRepository
import cloud.trotter.log.gig.core.data.log.LogRepository
import cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.ConsentReceiptReader
import cloud.trotter.log.gig.domain.capability.StoredConsentReceipts
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicles
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/**
 * Drives the free-tier CSV export (#319). Reads the analytics read-model via [AnalyticsRepository],
 * formats it with the pure [CsvExporter], and writes five files into a user-chosen directory
 * (Storage Access Framework tree — no storage permission, no network). The formatting is pure and
 * lives in the data/domain layers; only the SAF write IO is here at the edge.
 *
 * P7 logging: counts only — number of files written and byte totals are counters, never row
 * contents; store/merchant names and any economics stay out of the log.
 */
@HiltViewModel
class DataExportViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val ledgerRepository: LedgerRepository,
    private val logRepository: LogRepository,
    private val dashVehicleRepository: DashVehicleRepository,
    private val garageRepository: GarageRepository,
    private val capabilityGrants: RuleCapabilityGrants,
    private val receiptReader: ConsentReceiptReader,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface ExportState {
        data object Idle : ExportState
        data object InProgress : ExportState
        data class Success(val filesWritten: Int) : ExportState

        /**
         * [errorClass] is the exception's simple class name — never its message (#944). A platform
         * exception message can embed the SAF folder URI, i.e. a user path, which is exactly why
         * the log below records only the class; the on-screen copy is the same disclosure surface
         * (a screenshotted/shared error is as public as an exported log line), so it gets the same
         * treatment. The class name is diagnostic and path-free.
         */
        data class Error(val errorClass: String) : ExportState
    }

    /** Bug-report (shareable log) export state (#551), separate from the CSV export state. */
    sealed interface LogExportState {
        data object Idle : LogExportState
        data object InProgress : LogExportState
        /** [scrubbedLines] = INFO+ lines the sink-gate redacted (surfaced for honesty). */
        data class Success(val scrubbedLines: Int) : LogExportState

        /** [errorClass] — same path-free disclosure rule as [ExportState.Error] (#944). */
        data class Error(val errorClass: String) : LogExportState
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state = _state.asStateFlow()

    private val _logState = MutableStateFlow<LogExportState>(LogExportState.Idle)
    val logState = _logState.asStateFlow()

    /** Export all-time analytics into the SAF directory [treeUri] as five CSV files. */
    fun export(treeUri: Uri) {
        if (_state.value == ExportState.InProgress) return
        _state.value = ExportState.InProgress
        viewModelScope.launch {
            val result = runCatching {
                // Whole build (DB reads + string assembly) AND write off the main thread.
                withContext(ioDispatcher) {
                    val bundle = analyticsRepository.buildCsvExport(
                        zone = ZoneId.systemDefault(),
                        generatedAtMillis = System.currentTimeMillis(),
                        expenses = ledgerRepository.allExpenses(),
                        fuel = ledgerRepository.allFuel(),
                        dashVehicles = DashVehicles.perDash(dashVehicleRepository.stamps.first()),
                        vehicles = garageRepository.garage.first().let { if (it.readable) it.vehicles else emptyList() },
                    )
                    writeBundle(treeUri, bundle)
                }
            }
            _state.value = result.fold(
                onSuccess = { count ->
                    Timber.tag("Export").i("CSV export wrote %d files", count)
                    ExportState.Success(count)
                },
                onFailure = { e ->
                    // P7: ERROR ships in exported bug reports. Platform exception messages can
                    // embed the SAF folder URI (a user-path), so log only the exception CLASS —
                    // never the raw message/stack-message. The UI state carries the same class
                    // (#944): the raw message used to be rendered on screen two lines under this
                    // very comment.
                    Timber.tag("Export").e(
                        "CSV export failed: %s", e.javaClass.simpleName
                    )
                    ExportState.Error(e.javaClass.simpleName)
                },
            )
        }
    }

    fun reset() {
        _state.value = ExportState.Idle
    }

    /**
     * Export the PII-safe shareable log (#551) as a single `giglog-bug-report.txt` bug report into the
     * SAF directory [treeUri]. The content is the shareable sink's current contents — INFO+
     * milestones only, already scrubbed at the sink — with a header comment. Never the firehose.
     *
     * #170: a "Consent receipts" section follows the log: each automatic-tap decision and the Screen events
     * decision, with its date, app version and privacy notice version. Those lines never passed the sink, so
     * they go through the same scrubber here ([LogRepository.scrubForExport]); if it fails, the section is
     * left out and the file says so. The receipts are read from their stores now ([ConsentReceiptReader]), never
     * from the caches; a read that fails says the records are unavailable, never "no choices".
     */
    fun exportLog(treeUri: Uri) {
        if (_logState.value == LogExportState.InProgress) return
        _logState.value = LogExportState.InProgress
        viewModelScope.launch {
            val scrubbed = logRepository.autoScrubbedLineCount
            val result = runCatching {
                withContext(ioDispatcher) {
                    val body = logRepository.shareableLogContents()
                    val receipts = consentReceiptsSection(storedReceiptLines(), logRepository::scrubForExport)
                    val content = buildLogFile(body, scrubbed, receipts)
                    writeSingleFile(treeUri, LOG_FILENAME, "text/plain", content)
                }
            }
            _logState.value = result.fold(
                onSuccess = {
                    // P7: counts only — never the log body. Non-zero scrub count is itself signal.
                    Timber.tag("Export").i("Bug-report log exported (auto-scrubbed=%d)", scrubbed)
                    LogExportState.Success(scrubbed)
                },
                onFailure = { e ->
                    // P7 + #944: log AND display the exception CLASS only — a message can embed
                    // the SAF folder URI.
                    Timber.tag("Export").e("Log export failed: %s", e.javaClass.simpleName)
                    LogExportState.Error(e.javaClass.simpleName)
                },
            )
        }
    }

    fun resetLog() {
        _logState.value = LogExportState.Idle
    }

    /**
     * Header comment + shareable body, then the consent receipts section ([receipts]). Header-only body when the
     * log is empty (CSV parity).
     */
    internal fun buildLogFile(body: String, scrubbedLines: Int, receipts: String): String = buildString {
        append("# gig.log bug report: the app's main events, with known private details removed.\n")
        append("# Private details were removed from $scrubbedLines line(s).\n")
        append("# Generated ")
        append(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))
        append("\n\n")
        append(body)
        if (body.isNotEmpty() && !body.endsWith("\n")) append("\n")
        append("\n")
        append(receipts)
    }

    /**
     * #170 — the stored consent receipts as lines, labelled with the same strings Automation & consent shows; null
     * when the stores can't be read. The capability list only names an action and rule beside a key.
     */
    private suspend fun storedReceiptLines(): List<String>? {
        val stored = try {
            receiptReader.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // P7: the class only; a storage message can carry a path.
            Timber.tag("Export").w("Consent receipts unreadable for the bug report: %s", e.javaClass.simpleName)
            return null
        }
        return receiptLines(stored)
    }

    private fun receiptLines(stored: StoredConsentReceipts) = consentReceiptLines(
        eventReceipt = stored.screenEvents,
        censusReceipt = stored.census,
        reviewsReceipt = stored.reviews,
        receipts = stored.capabilities,
        capabilities = capabilityGrants.capabilities.value,
        labels = ReceiptLabels(
            allowed = context.getString(R.string.consent_receipt_allowed),
            denied = context.getString(R.string.consent_receipt_denied),
            format = context.getString(R.string.consent_receipt_format),
            screenEvents = context.getString(R.string.event_receipt_title),
            census = context.getString(R.string.census_title),
            reviews = context.getString(R.string.census_review_title),
        ),
        zone = ZoneId.systemDefault(),
        locale = Locale.getDefault(),
    )

    /** Writes one text file into the tree, overwriting any same-named prior export. */
    private fun writeSingleFile(treeUri: Uri, name: String, mime: String, content: String) {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        deleteIfExists(treeUri, treeDocId, name)
        val fileUri = DocumentsContract.createDocument(resolver, dirUri, mime, name)
            ?: error("Could not create $name in the chosen folder")
        resolver.openOutputStream(fileUri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open $name for writing")
    }

    /** Writes the five CSVs into the tree, overwriting same-named files. Returns files written. */
    private fun writeBundle(treeUri: Uri, bundle: CsvExporter.Bundle): Int {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        var written = 0
        listOf(
            CsvExporter.DELIVERIES_FILENAME to bundle.deliveriesCsv,
            CsvExporter.SESSIONS_FILENAME to bundle.sessionsCsv,
            CsvExporter.SUMMARY_FILENAME to bundle.summaryCsv,
            CsvExporter.EXPENSES_FILENAME to bundle.expensesCsv,
            CsvExporter.FUEL_FILENAME to bundle.fuelCsv,
        ).forEach { (name, content) ->
            // Replace any prior export of the same name so re-exporting isn't cumulative clutter.
            deleteIfExists(treeUri, treeDocId, name)
            val fileUri = DocumentsContract.createDocument(resolver, dirUri, "text/csv", name)
                ?: error("Could not create $name in the chosen folder")
            resolver.openOutputStream(fileUri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open $name for writing")
            written++
        }
        return written
    }

    /** Best-effort delete of a prior same-named file in the tree so we overwrite, not duplicate. */
    private fun deleteIfExists(treeUri: Uri, treeDocId: String, displayName: String) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == displayName) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                        DocumentsContract.deleteDocument(resolver, childUri)
                    }
                }
            }
        }
    }

    private companion object {
        const val LOG_FILENAME = "giglog-bug-report.txt"
    }
}

/** The words a receipt line uses: the decision labels, `consent_receipt_format`, the Screen events and census names. */
internal data class ReceiptLabels(
    val allowed: String,
    val denied: String,
    val format: String,
    val screenEvents: String,
    val census: String,
    val reviews: String = "",
)

/**
 * #170 — one line per stored receipt, in the `consent_receipt_format` shape Automation & consent shows: the
 * Screen events decision first, then the census decision (#1621), then screen reviews (#1625), then each automatic-tap decision by capability key. A key the loaded rules
 * enumerate also names its action and rule; a key they no longer enumerate is listed by key alone. Keys are
 * sha256 hashes, rule ids and action names are app vocabulary: no screen text.
 */
internal fun consentReceiptLines(
    eventReceipt: ConsentReceipt?,
    censusReceipt: ConsentReceipt?,
    receipts: Map<String, ConsentReceipt>,
    reviewsReceipt: ConsentReceipt? = null,
    capabilities: List<RuleCapability>,
    labels: ReceiptLabels,
    zone: ZoneId,
    locale: Locale,
): List<String> {
    fun line(subject: String, receipt: ConsentReceipt) = "$subject: " + formatConsentStatus(
        status = if (receipt.granted) labels.allowed else labels.denied,
        receipt = receipt,
        format = labels.format,
        zone = zone,
        locale = locale,
    )
    val byKey = capabilities.associateBy { it.key }
    return buildList {
        eventReceipt?.let { add(line(labels.screenEvents, it)) }
        censusReceipt?.let { add(line(labels.census, it)) }
        reviewsReceipt?.let { add(line(labels.reviews, it)) }
        receipts.toSortedMap().forEach { (key, receipt) ->
            val capability = byKey[key]
            val subject = if (capability == null) "Capability $key" else "${capability.action.wire} on ${capability.ruleId} ($key)"
            add(line(subject, receipt))
        }
    }
}

/**
 * #170 — the bug report's consent receipts section. [lines] null means the stores could not be read, which the
 * section says, never "no choices". [scrub] is the shareable sink's scrubber; null from it means the scrubber
 * failed, so the receipts are left out and the section says so. Never the lines unscrubbed.
 */
internal fun consentReceiptsSection(lines: List<String>?, scrub: (List<String>) -> List<String>?): String = buildString {
    append("# Consent receipts: each choice, its date, the app version and the privacy notice version.\n")
    when {
        lines == null -> append("# Consent records unavailable: they could not be read.\n")
        lines.isEmpty() -> append("# No choices recorded.\n")
        else -> {
            val scrubbed = scrub(lines)
            if (scrubbed == null) {
                append("# Left out: the privacy filter failed.\n")
            } else {
                scrubbed.forEach { append(it).append('\n') }
            }
        }
    }
}
