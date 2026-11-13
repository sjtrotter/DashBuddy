package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What [BackupWriter.write] wrote: the event log's row count, the schema version and the zip's size in bytes. */
data class BackupSummary(val eventCount: Long, val dbVersion: Int, val bytes: Long)

/**
 * Writes the full backup file (#1615, [BackupFormat]).
 *
 * The database copy comes from SQLite's `VACUUM INTO` on Room's own connection, so it is one consistent read with the
 * WAL folded in, taken while the app keeps running. The copy then loses its crash-recovery tables
 * (`app_state_snapshots`, `observations`): a restore starts with no shift running. The backed-up DataStore files are
 * copied as they stand; DataStore replaces a file by rename, so each read is one whole version of it.
 *
 * Logging (tag `Backup`): one INFO with counts and versions only.
 */
@Singleton
class BackupWriter @Inject constructor(
    private val db: GigLogDatabase,
    @param:ApplicationContext private val context: Context,
) {

    /** Writes the backup zip to [out] and leaves [out] open. [appVersion] is this build's versionName. */
    suspend fun write(out: OutputStream, appVersion: String): BackupSummary = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, WORK_DIR)
        try {
            work.deleteRecursively()
            work.mkdirs()
            val copy = File(work, BackupFormat.DB_ENTRY)
            db.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf<Any>(copy.path))
            val (dbVersion, eventCount) = SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE)
                .use { copyDb ->
                    copyDb.execSQL("DELETE FROM app_state_snapshots")
                    copyDb.execSQL("DELETE FROM observations")
                    val count = copyDb.rawQuery("SELECT COUNT(*) FROM app_events", null)
                        .use { if (it.moveToFirst()) it.getLong(0) else 0L }
                    copyDb.version to count
                }

            val stores = DataStoreFiles.BACKED_UP.sorted()
                .map { it to BackupFormat.datastoreFile(context, it) }
                .filter { (_, file) -> file.isFile }
                .map { (name, file) -> BackupFormat.datastoreEntry(name) to file }
            val manifest = BackupManifest(
                format = BackupFormat.FORMAT,
                dbVersion = dbVersion,
                appVersion = appVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                files = listOf(BackupFormat.DB_ENTRY) + stores.map { it.first },
            )

            val counting = CountingOutputStream(out)
            ZipOutputStream(counting).use { zip ->
                zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
                zip.write(Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((entry, file) in listOf(BackupFormat.DB_ENTRY to copy) + stores) {
                    zip.putNextEntry(ZipEntry(entry))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            Timber.tag(TAG).i(
                "backup written: %d events, db v%d, %d settings files, %d bytes",
                eventCount, dbVersion, stores.size, counting.count,
            )
            BackupSummary(eventCount = eventCount, dbVersion = dbVersion, bytes = counting.count)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Counts the bytes written through it; [close] does not close the caller's stream. */
    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun close() = flush()
    }

    private companion object {
        const val TAG = "Backup"
        const val WORK_DIR = "backup-work"
    }
}
