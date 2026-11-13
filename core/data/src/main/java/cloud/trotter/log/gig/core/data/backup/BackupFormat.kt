package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * The full backup file (#1615): one zip holding [MANIFEST_ENTRY], [DB_ENTRY] and a `datastore/<name>.preferences_pb`
 * entry for each [DataStoreFiles.BACKED_UP] store that exists. [BackupWriter] writes it, [BackupRestorer] checks and
 * stages it, and [RestoreSwap] puts the staged files in place at the next start.
 */
internal object BackupFormat {
    const val FORMAT = 1

    /**
     * The oldest schema a backup may carry: the version this feature shipped at. Every later schema bump carries a
     * migration (`SchemaVersionGuardTest` chains them), so a backup from `27..GigLogDatabase.VERSION` always opens.
     */
    const val FIRST_BACKUP_DB_VERSION = 27

    /**
     * Room's `identity_hash` for each schema a backup may carry, from the exported schema JSON
     * (`core/database/schemas/`). A staged database must carry the hash of its own `user_version`, so a file that
     * only claims gig.log's version number is refused. `KnownIdentityHashesTest` fails until a schema bump adds its
     * version here.
     */
    val KNOWN_IDENTITY_HASHES: Map<Int, String> = mapOf(
        27 to "feedfd7bd8b830b57349761c791d8829",
    )

    /** The manifest's `appVersion`, shown in the confirm dialog: a bounded version string or the file is refused. */
    val APP_VERSION_SHAPE = Regex("[0-9A-Za-z.+_-]{1,64}")

    const val MANIFEST_ENTRY = "manifest.json"
    const val DB_ENTRY = GigLogDatabase.NAME
    private const val DATASTORE_PREFIX = "datastore/"
    private const val DATASTORE_SUFFIX = ".preferences_pb"

    const val MANIFEST_CAP = 64L * 1024
    const val DATASTORE_CAP = 4L * 1024 * 1024
    const val DB_CAP = 1024L * 1024 * 1024
    const val ARCHIVE_CAP = DB_CAP + 64L * 1024 * 1024

    /** Under `filesDir`: the checked backup waiting for the next start. */
    const val STAGING_DIR = "restore-staging"

    /** Under `filesDir`: where a stage is built before it is renamed to [STAGING_DIR]. */
    const val STAGING_TMP_DIR = "restore-staging.tmp"

    /** Under `filesDir`: the files the last restore replaced. One copy. */
    const val PREVIOUS_DIR = "restore-previous"

    /** Under `filesDir`: the last swap's outcome, read once by the backup screen. */
    const val RESULT_FILE = "restore-result"

    /** In [STAGING_DIR], written last: the stage is complete and confirmed. Holds the backup's `createdAtEpochMs`. */
    const val READY_FILE = "ready"

    const val RESULT_OK = "ok"
    const val RESULT_FAILED = "failed"

    fun datastoreEntry(name: String): String = "$DATASTORE_PREFIX$name$DATASTORE_SUFFIX"

    /** The backed-up store [entry] names, or null when [entry] is not `datastore/<BACKED_UP name>.preferences_pb`. */
    fun datastoreName(entry: String): String? =
        DataStoreFiles.BACKED_UP.firstOrNull { datastoreEntry(it) == entry }

    /** Writes [bytes] to [file] and syncs it to disk before returning. */
    fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** The live file of the backed-up store [name]. */
    fun datastoreFile(context: Context, name: String): File = context.preferencesDataStoreFile(name)
}

/** The backup file's `manifest.json`. [files] names every other entry in the zip. */
@Serializable
internal data class BackupManifest(
    val format: Int,
    val dbVersion: Int,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val files: List<String>,
)
