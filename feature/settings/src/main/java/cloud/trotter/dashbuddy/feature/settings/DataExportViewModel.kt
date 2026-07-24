package cloud.trotter.dashbuddy.feature.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CsvExporter
import cloud.trotter.dashbuddy.core.data.log.LogRepository
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.ZoneId
import javax.inject.Inject

/**
 * Drives the free-tier CSV export (#319). Reads the analytics read-model via [AnalyticsRepository],
 * formats it with the pure [CsvExporter], and writes three files into a user-chosen directory
 * (Storage Access Framework tree — no storage permission, no network). The formatting is pure and
 * lives in the data/domain layers; only the SAF write IO is here at the edge.
 *
 * P7 logging: counts only — number of files written and byte totals are counters, never row
 * contents; store/merchant names and any economics stay out of the log.
 */
@HiltViewModel
class DataExportViewModel @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val logRepository: LogRepository,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface ExportState {
        data object Idle : ExportState
        data object InProgress : ExportState
        data class Success(val filesWritten: Int) : ExportState
        data class Error(val message: String) : ExportState
    }

    /** Bug-report (shareable log) export state (#551), separate from the CSV export state. */
    sealed interface LogExportState {
        data object Idle : LogExportState
        data object InProgress : LogExportState
        /** [scrubbedLines] = INFO+ lines the sink-gate redacted (surfaced for honesty). */
        data class Success(val scrubbedLines: Int) : LogExportState
        data class Error(val message: String) : LogExportState
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state = _state.asStateFlow()

    private val _logState = MutableStateFlow<LogExportState>(LogExportState.Idle)
    val logState = _logState.asStateFlow()

    /** Export all-time analytics into the SAF directory [treeUri] as three CSV files. */
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
                    // never the raw message/stack-message.
                    Timber.tag("Export").e(
                        "CSV export failed: %s", e.javaClass.simpleName
                    )
                    ExportState.Error(e.message ?: "Export failed")
                },
            )
        }
    }

    fun reset() {
        _state.value = ExportState.Idle
    }

    /**
     * Export the PII-safe shareable log (#551) as a single `dashbuddy-log.txt` bug report into the
     * SAF directory [treeUri]. The content is the shareable sink's current contents — INFO+
     * milestones only, already scrubbed at the sink — with a header comment. Never the firehose.
     */
    fun exportLog(treeUri: Uri) {
        if (_logState.value == LogExportState.InProgress) return
        _logState.value = LogExportState.InProgress
        viewModelScope.launch {
            val scrubbed = logRepository.autoScrubbedLineCount
            val result = runCatching {
                withContext(ioDispatcher) {
                    val body = logRepository.shareableLogContents()
                    val content = buildLogFile(body, scrubbed)
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
                    // P7: log the exception CLASS only — messages can embed the SAF folder URI.
                    Timber.tag("Export").e("Log export failed: %s", e.javaClass.simpleName)
                    LogExportState.Error(e.message ?: "Export failed")
                },
            )
        }
    }

    fun resetLog() {
        _logState.value = LogExportState.Idle
    }

    /** Header comment + shareable body. Header-only file when the log is empty (CSV parity). */
    internal fun buildLogFile(body: String, scrubbedLines: Int): String = buildString {
        append("# DashBuddy bug report — INFO+ milestones only (no raw store/customer text).\n")
        append("# $scrubbedLines line(s) were auto-scrubbed by the fail-closed sink gate.\n")
        append("# Generated ")
        append(java.time.Instant.ofEpochMilli(System.currentTimeMillis()))
        append("\n\n")
        append(body)
    }

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

    /** Writes the three CSVs into the tree, overwriting same-named files. Returns files written. */
    private fun writeBundle(treeUri: Uri, bundle: CsvExporter.Bundle): Int {
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        var written = 0
        listOf(
            CsvExporter.DELIVERIES_FILENAME to bundle.deliveriesCsv,
            CsvExporter.SESSIONS_FILENAME to bundle.sessionsCsv,
            CsvExporter.SUMMARY_FILENAME to bundle.summaryCsv,
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
        const val LOG_FILENAME = "dashbuddy-log.txt"
    }
}
