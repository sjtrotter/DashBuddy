package cloud.trotter.dashbuddy.ui.main.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CsvExporter
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
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface ExportState {
        data object Idle : ExportState
        data object InProgress : ExportState
        data class Success(val filesWritten: Int) : ExportState
        data class Error(val message: String) : ExportState
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state = _state.asStateFlow()

    /** Export all-time analytics into the SAF directory [treeUri] as three CSV files. */
    fun export(treeUri: Uri) {
        if (_state.value == ExportState.InProgress) return
        _state.value = ExportState.InProgress
        viewModelScope.launch {
            val result = runCatching {
                val bundle = analyticsRepository.buildCsvExport(
                    zone = ZoneId.systemDefault(),
                    generatedAtMillis = System.currentTimeMillis(),
                )
                withContext(ioDispatcher) { writeBundle(treeUri, bundle) }
            }
            _state.value = result.fold(
                onSuccess = { count ->
                    Timber.tag("Export").i("CSV export wrote %d files", count)
                    ExportState.Success(count)
                },
                onFailure = { e ->
                    Timber.tag("Export").e(e, "CSV export failed")
                    ExportState.Error(e.message ?: "Export failed")
                },
            )
        }
    }

    fun reset() {
        _state.value = ExportState.Idle
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
}
