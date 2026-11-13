package cloud.trotter.log.gig.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.backup.BackupRestorer
import cloud.trotter.log.gig.core.data.backup.BackupWriter
import cloud.trotter.log.gig.core.data.backup.RestoreOutcome
import cloud.trotter.log.gig.core.data.backup.RestoreRefusal
import cloud.trotter.log.gig.core.data.backup.StageResult
import cloud.trotter.log.gig.domain.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named

/**
 * Drives the full backup (#1615): writes the backup file to a document the driver creates, and checks and stages a
 * backup file the driver opens. The file work lives in [BackupWriter] and [BackupRestorer]; this class only opens the
 * picked document and maps each result to a screen state. A confirmed restore ends in [RestoreState.Restarting]; the
 * screen restarts the app, and `RestoreSwap` puts the files in place at the next start.
 *
 * P7 logging (tag `Backup`): the exception class only, never a message (a message can carry the document URI).
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val writer: BackupWriter,
    private val restorer: BackupRestorer,
    @param:ApplicationContext private val context: Context,
    @param:Named("appVersionName") private val appVersion: String,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    sealed interface BackupState {
        data object Idle : BackupState
        data object InProgress : BackupState
        data class Done(val eventCount: Long) : BackupState

        /** [errorClass] is the exception's class name, never its message (the #944 rule of the CSV export). */
        data class Failed(val errorClass: String) : BackupState
    }

    sealed interface RestoreState {
        data object Idle : RestoreState
        data object Checking : RestoreState

        /** A checked backup waits for the driver: the date it was made and the gig.log version that made it. */
        data class Confirm(val createdAtEpochMs: Long, val appVersion: String) : RestoreState
        data class Refused(val reason: RestoreRefusal) : RestoreState

        /** Confirmed: the screen restarts the app to finish. */
        data object Restarting : RestoreState
    }

    private val _backup = MutableStateFlow<BackupState>(BackupState.Idle)
    val backup: StateFlow<BackupState> = _backup.asStateFlow()

    private val _restore = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restore: StateFlow<RestoreState> = _restore.asStateFlow()

    /** The last restore's outcome, read once when the screen opens. Null when there was none. */
    private val _lastRestore = MutableStateFlow<RestoreOutcome?>(null)
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    init {
        viewModelScope.launch { _lastRestore.value = restorer.takeResult() }
    }

    /** Writes the backup into the document [uri] the driver created. */
    fun backUp(uri: Uri) {
        if (_backup.value == BackupState.InProgress) return
        _backup.value = BackupState.InProgress
        viewModelScope.launch {
            _backup.value = try {
                val summary = withContext(ioDispatcher) {
                    context.contentResolver.openOutputStream(uri)?.use { writer.write(it, appVersion) }
                        ?: error("no output stream")
                }
                BackupState.Done(summary.eventCount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e("backup failed: %s", e.javaClass.simpleName)
                BackupState.Failed(e.javaClass.simpleName)
            }
        }
    }

    /** Checks and stages the backup in the document [uri]; a pass asks the driver to confirm. */
    fun restore(uri: Uri) {
        if (_restore.value == RestoreState.Checking) return
        _restore.value = RestoreState.Checking
        viewModelScope.launch {
            val result = try {
                withContext(ioDispatcher) {
                    context.contentResolver.openInputStream(uri)?.use { restorer.stage(it) }
                } ?: StageResult.Refused(RestoreRefusal.READ_FAILED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("backup file unreadable: %s", e.javaClass.simpleName)
                StageResult.Refused(RestoreRefusal.READ_FAILED)
            }
            _restore.value = when (result) {
                is StageResult.Staged -> RestoreState.Confirm(result.createdAtEpochMs, result.appVersion)
                is StageResult.Refused -> RestoreState.Refused(result.reason)
            }
        }
    }

    /** The driver confirmed the replace: mark the stage ready, then the screen restarts the app. */
    fun confirmRestore() {
        if (_restore.value !is RestoreState.Confirm) return
        viewModelScope.launch {
            _restore.value = if (restorer.confirm()) RestoreState.Restarting else RestoreState.Refused(RestoreRefusal.READ_FAILED)
        }
    }

    /** The driver cancelled: the stage is deleted and nothing changes. */
    fun cancelRestore() {
        viewModelScope.launch {
            restorer.discard()
            _restore.value = RestoreState.Idle
        }
    }

    internal companion object {
        private const val TAG = "Backup"

        private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

        /** The suggested backup file name, `giglog-backup-<yyyy-MM-dd-HHmm>.zip`, in the device zone. */
        fun suggestedFileName(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "giglog-backup-${FILE_STAMP.format(Instant.ofEpochMilli(nowMs).atZone(zone))}.zip"
    }
}
