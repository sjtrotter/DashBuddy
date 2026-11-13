package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Why a backup file was refused. Each has its own line of copy on the backup screen. */
enum class RestoreRefusal { NOT_A_BACKUP, NEWER_APP, NO_SPACE, READ_FAILED }

/** The outcome of [BackupRestorer.stage]. */
sealed interface StageResult {
    /** Checked and staged: the backup's creation time, the gig.log version that wrote it and its event count. */
    data class Staged(val createdAtEpochMs: Long, val appVersion: String, val eventCount: Long) : StageResult

    data class Refused(val reason: RestoreRefusal) : StageResult
}

/** The last swap's outcome, as [BackupRestorer.takeResult] reads it. */
sealed interface RestoreOutcome {
    /** The data now on the phone came from the backup made at [createdAtEpochMs] (null when unreadable). */
    data class Restored(val createdAtEpochMs: Long?) : RestoreOutcome

    /** The swap failed part way; every file was put back. */
    data object Failed : RestoreOutcome
}

/**
 * Checks a backup file and stages it for [RestoreSwap] (#1615).
 *
 * The file is untrusted input. [stage] unpacks it into a temp dir under `filesDir`, counting bytes as it copies
 * (declared sizes are never read), and accepts only the entry names [BackupFormat] defines. The staged database must
 * pass `PRAGMA quick_check`, hold an `app_events` table and carry the manifest's schema version, inside
 * `FIRST_BACKUP_DB_VERSION..GigLogDatabase.VERSION`. Only when every check passes is the temp dir renamed to
 * `restore-staging/`; a refusal leaves the current data and any earlier stage as they were.
 *
 * A stage is inert until [confirm] writes its `ready` marker, after the driver confirms the replace. A stage left
 * without a marker (the dialog cancelled, the process killed under it) is deleted at the next start, never applied.
 *
 * Logging (tag `Backup`): INFO for staged and refused, with counts, versions and the refusal's name only.
 */
@Singleton
class BackupRestorer internal constructor(
    private val context: Context,
    private val freeBytes: (File) -> Long,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, File::getUsableSpace)

    private val filesDir: File get() = context.filesDir

    /** Checks the backup in [input] and stages it without its `ready` marker. Closes [input]. */
    suspend fun stage(input: InputStream): StageResult = withContext(Dispatchers.IO) {
        val tmp = File(filesDir, BackupFormat.STAGING_TMP_DIR)
        try {
            tmp.deleteRecursively()
            if (!tmp.mkdirs()) throw IOException("could not create the staging dir")
            val staged = check(unpack(input, tmp), tmp)
            if (freeBytes(filesDir) < sizeOf(tmp) + currentBytes()) throw Refusal(RestoreRefusal.NO_SPACE)
            val staging = File(filesDir, BackupFormat.STAGING_DIR)
            staging.deleteRecursively()
            if (!tmp.renameTo(staging)) throw IOException("could not move the stage into place")
            Timber.tag(TAG).i(
                "restore staged: %d events, db v%d, from gig.log %s",
                staged.result.eventCount, staged.dbVersion, staged.result.appVersion,
            )
            staged.result
        } catch (r: Refusal) {
            Timber.tag(TAG).i("restore refused %s", r.reason.name)
            StageResult.Refused(r.reason)
        } catch (e: IOException) {
            Timber.tag(TAG).i("restore refused %s (%s)", RestoreRefusal.READ_FAILED.name, e.javaClass.simpleName)
            StageResult.Refused(RestoreRefusal.READ_FAILED)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Marks the stage ready for [RestoreSwap] at the next start: the driver confirmed the replace. The marker is
     * written last and synced. Returns false when no complete stage is waiting.
     */
    suspend fun confirm(): Boolean = withContext(Dispatchers.IO) {
        val staging = File(filesDir, BackupFormat.STAGING_DIR)
        val manifest = File(staging, BackupFormat.MANIFEST_ENTRY)
        if (!File(staging, BackupFormat.DB_ENTRY).isFile || !manifest.isFile) return@withContext false
        try {
            val createdAt = Json.decodeFromString(BackupManifest.serializer(), manifest.readText()).createdAtEpochMs
            writeSynced(File(staging, BackupFormat.READY_FILE), createdAt.toString().toByteArray(Charsets.UTF_8))
            true
        } catch (e: IOException) {
            Timber.tag(TAG).w("restore confirm failed: %s", e.javaClass.simpleName)
            false
        } catch (e: SerializationException) {
            Timber.tag(TAG).w("restore confirm failed: %s", e.javaClass.simpleName)
            false
        }
    }

    /** Deletes a waiting stage (the driver cancelled). */
    suspend fun discard() {
        withContext(Dispatchers.IO) { File(filesDir, BackupFormat.STAGING_DIR).deleteRecursively() }
    }

    /** The last swap's outcome, read once: the file is deleted. Null when no swap has run since the last read. */
    suspend fun takeResult(): RestoreOutcome? = withContext(Dispatchers.IO) {
        val file = File(filesDir, BackupFormat.RESULT_FILE)
        if (!file.isFile) return@withContext null
        val text = runCatching { file.readText().trim() }.getOrNull()
        file.delete()
        when {
            text == null -> null
            text.startsWith(BackupFormat.RESULT_OK) ->
                RestoreOutcome.Restored(text.removePrefix(BackupFormat.RESULT_OK).trim().toLongOrNull())
            else -> RestoreOutcome.Failed
        }
    }

    /** The manifest's bytes (null when absent) and every other entry name the zip held. */
    private class Unpacked(val manifest: ByteArray?, val entries: Set<String>)

    /** Copies each allowed entry of the zip in [input] into [dir]. */
    private fun unpack(input: InputStream, dir: File): Unpacked {
        var manifest: ByteArray? = null
        var total = 0L
        val seen = HashSet<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !seen.add(name) || !isSafeName(name)) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
                val budget = BackupFormat.ARCHIVE_CAP - total
                total += when {
                    name == BackupFormat.MANIFEST_ENTRY -> {
                        val bytes = ByteArrayOutputStream()
                        val n = copyCapped(zip, bytes, minOf(BackupFormat.MANIFEST_CAP, budget))
                        manifest = bytes.toByteArray()
                        n
                    }
                    name == BackupFormat.DB_ENTRY -> copyToFile(zip, File(dir, name), minOf(BackupFormat.DB_CAP, budget))
                    BackupFormat.datastoreName(name) != null ->
                        copyToFile(zip, File(dir, name), minOf(BackupFormat.DATASTORE_CAP, budget))
                    else -> throw Refusal(RestoreRefusal.NOT_A_BACKUP)
                }
            }
        }
        manifest?.let { writeSynced(File(dir, BackupFormat.MANIFEST_ENTRY), it) }
        return Unpacked(manifest, seen - BackupFormat.MANIFEST_ENTRY)
    }

    private class Checked(val result: StageResult.Staged, val dbVersion: Int)

    private fun check(unpacked: Unpacked, dir: File): Checked {
        val bytes = unpacked.manifest ?: throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        val manifest = try {
            Json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        } catch (_: IllegalArgumentException) {
            throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        }
        if (manifest.format != BackupFormat.FORMAT) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        if (manifest.files.size != manifest.files.toSet().size || manifest.files.toSet() != unpacked.entries) {
            throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        }
        val db = File(dir, BackupFormat.DB_ENTRY)
        if (!db.isFile) throw Refusal(RestoreRefusal.NOT_A_BACKUP)

        val (version, eventCount) = inspect(db)
        if (version != manifest.dbVersion) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        if (version > GigLogDatabase.VERSION) throw Refusal(RestoreRefusal.NEWER_APP)
        if (version < BackupFormat.FIRST_BACKUP_DB_VERSION) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
        return Checked(
            StageResult.Staged(manifest.createdAtEpochMs, manifest.appVersion, eventCount),
            version,
        )
    }

    /**
     * Opens the staged copy read-only: integrity, an `app_events` table, then its `user_version` and event count. The
     * no-op error handler keeps Android's default handler from deleting a corrupt file; a refusal deletes it anyway.
     */
    private fun inspect(db: File): Pair<Int, Long> = try {
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { sql ->
            val integrity = sql.rawQuery("PRAGMA quick_check", null).use { if (it.moveToFirst()) it.getString(0) else null }
            if (integrity != "ok") throw Refusal(RestoreRefusal.NOT_A_BACKUP)
            val hasEvents = sql.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'app_events'",
                null,
            ).use { it.moveToFirst() && it.getLong(0) == 1L }
            if (!hasEvents) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
            val count = sql.rawQuery("SELECT COUNT(*) FROM app_events", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            sql.version to count
        }
    } catch (_: SQLiteException) {
        throw Refusal(RestoreRefusal.NOT_A_BACKUP)
    }

    /** The bytes the current install holds in the files a restore replaces. */
    private fun currentBytes(): Long {
        val dbFile = context.getDatabasePath(GigLogDatabase.NAME)
        val dbFiles = listOf("", "-wal", "-shm").map { File(dbFile.path + it) }
        val stores = DataStoreFiles.BACKED_UP.map { BackupFormat.datastoreFile(context, it) }
        return (dbFiles + stores).sumOf { if (it.isFile) it.length() else 0L }
    }

    private class Refusal(val reason: RestoreRefusal) : Exception(reason.name)

    private companion object {
        const val TAG = "Backup"

        /** No traversal, no absolute path, no Windows separator: the allow-list check runs after this one. */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && !name.contains("..") && !name.startsWith("/") && !name.contains('\\')

        /** Copies at most [cap] bytes; one more byte than that refuses the file. Returns the bytes copied. */
        fun copyCapped(from: InputStream, to: OutputStream, cap: Long): Long {
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val n = from.read(buffer)
                if (n < 0) return copied
                copied += n
                if (copied > cap) throw Refusal(RestoreRefusal.NOT_A_BACKUP)
                to.write(buffer, 0, n)
            }
        }

        fun copyToFile(from: InputStream, file: File, cap: Long): Long {
            file.parentFile?.mkdirs()
            return FileOutputStream(file).use { out ->
                val n = copyCapped(from, out, cap)
                out.flush()
                out.fd.sync()
                n
            }
        }

        fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }

        fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
