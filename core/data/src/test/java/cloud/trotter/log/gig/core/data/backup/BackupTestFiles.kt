package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared file helpers for the #1615 backup tests. Synthetic bytes only. */
internal object BackupTestFiles {

    fun clean(context: Context) {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) context.getDatabasePath(GigLogDatabase.NAME + suffix).delete()
        for (name in DataStoreFiles.ALL) BackupFormat.datastoreFile(context, name).delete()
        for (dir in listOf(
            BackupFormat.STAGING_DIR, BackupFormat.STAGING_TMP_DIR, BackupFormat.PREVIOUS_DIR, BackupFormat.RESULT_FILE,
        )) {
            File(context.filesDir, dir).deleteRecursively()
        }
        File(context.cacheDir, "backup-work").deleteRecursively()
        File(context.cacheDir, "backup-test").deleteRecursively()
    }

    fun writeStore(context: Context, name: String, bytes: ByteArray) {
        val file = BackupFormat.datastoreFile(context, name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    fun readStore(context: Context, name: String): ByteArray? =
        BackupFormat.datastoreFile(context, name).takeIf { it.isFile }?.readBytes()

    /**
     * A real Room database's bytes holding [events] rows in `app_events`. [version] and [identityHash], when given,
     * overwrite `user_version` and Room's identity hash afterwards.
     */
    fun roomDbBytes(context: Context, events: Int = 2, version: Int? = null, identityHash: String? = null): ByteArray {
        val name = "fixture-${System.nanoTime()}.db"
        val room = Room.databaseBuilder(context, GigLogDatabase::class.java, name).allowMainThreadQueries().build()
        val sql = room.openHelper.writableDatabase
        repeat(events) { i ->
            sql.execSQL(
                "INSERT INTO app_events(aggregateId, eventType, eventPayload, occurredAt) VALUES (?, 'DASH_START', '{}', ?)",
                arrayOf<Any>("shift-$i", 1_000L + i),
            )
        }
        room.close()
        val file = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            version?.let { db.version = it }
            identityHash?.let { db.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(it)) }
        }
        return file.readBytes().also {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) File(file.path + suffix).delete()
        }
    }

    /** A valid Preferences DataStore file holding one string. */
    fun prefsBytes(value: String): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        PreferencesFileSerializer.writeTo(preferencesOf(stringPreferencesKey("k") to value), out)
        out.toByteArray()
    }

    /** A SQLite file's bytes at [version], with a two-column `app_events` table when [withEvents]; never a Room schema. */
    fun sqliteBytes(context: Context, version: Int, withEvents: Boolean = true, events: Int = 2): ByteArray {
        val dir = File(context.cacheDir, "backup-test").apply { mkdirs() }
        val file = File(dir, "db-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            if (withEvents) {
                db.execSQL("CREATE TABLE app_events(sequenceId INTEGER PRIMARY KEY, eventType TEXT)")
                repeat(events) { db.execSQL("INSERT INTO app_events(eventType) VALUES ('TEST')") }
            } else {
                db.execSQL("CREATE TABLE other(x TEXT)")
            }
            db.version = version
        }
        return file.readBytes().also { file.delete() }
    }

    fun manifest(
        dbVersion: Int = GigLogDatabase.VERSION,
        format: Int = BackupFormat.FORMAT,
        files: List<String> = listOf(BackupFormat.DB_ENTRY),
        createdAt: Long = 1_700_000_000_000L,
        appVersion: String = "0.0.0-test",
    ): ByteArray = Json.encodeToString(
        BackupManifest.serializer(),
        BackupManifest(format, dbVersion, appVersion, createdAt, files),
    ).toByteArray()

    /** A deflated zip of [entries], in order. */
    fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Local headers only, stored, no central directory: enough for `ZipInputStream`, and it allows what
     * `ZipOutputStream` refuses (a duplicate name).
     */
    fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            val header = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x04034b50).putShort(20).putShort(0).putShort(0).putShort(0).putShort(0)
                .putInt(crc.toInt()).putInt(data.size).putInt(data.size)
                .putShort(nameBytes.size.toShort()).putShort(0)
            out.write(header.array())
            out.write(nameBytes)
            out.write(data)
        }
        return out.toByteArray()
    }
}
