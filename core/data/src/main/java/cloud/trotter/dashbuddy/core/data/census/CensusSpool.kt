package cloud.trotter.dashbuddy.core.data.census

import android.content.Context
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** App-private, bounded, drop-oldest queue. Only the single sink consumer writes records. */
@Singleton
open class CensusSpool internal constructor(
    private val directory: File,
    private val stats: CensusUploadStats,
    private val io: CoroutineDispatcher,
    private val maxFiles: Int = 2_000,
    private val maxDiskBytes: Long = 20L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        stats: CensusUploadStats,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(File(context.filesDir, "census/spool"), stats, io)

    data class Spooled(val id: String, val fingerprint: String, val itemJson: String) {
        override fun toString(): String = "Spooled([redacted])"
    }

    data class InFlight(val batchId: String, val ids: List<String>) {
        override fun toString(): String = "InFlight([redacted])"
    }

    private val inFlightFile = File(directory.parentFile, "inflight.json")
    private val mutex = Mutex()
    private val size = MutableStateFlow(0)
    @Volatile private var initialized = false
    private var sequence = 0L

    open fun count(): Int = if (initialized) size.value else
        directory.listFiles().orEmpty().count { it.isFile && FILE_NAME.matches(it.name) }

    /**
     * Queued-record count for the developer screen; the first collection initializes the spool off the IO
     * dispatcher. Fail-OPEN: an unavailable spool directory reports 0 rather than failing the UI collector.
     */
    open val queued: Flow<Int> = flow {
        withContext(io) { mutex.withLock { runCatching { initialize() } } }
        emitAll(size)
    }

    open suspend fun append(record: CensusRecord) = withContext(io) {
        mutex.withLock {
            initialize()
            val name = "${now()}-${++sequence}.json"
            val target = File(directory, name)
            val tmp = File(directory, "$name.tmp")
            val capture = record.captureId?.let { JsonPrimitive(it).toString() } ?: "null"
            // The builder already serialized the item. Preserve its bytes, including property order.
            val wrapper = "{\"platform\":${JsonPrimitive(record.platform.wire)}," +
                "\"fingerprint\":${JsonPrimitive(record.fingerprint)},\"skeleton\":${record.skeletonJson},\"captureId\":$capture}"
            try {
                FileOutputStream(tmp).use {
                    it.write(wrapper.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                if (!tmp.renameTo(target)) throw IOException("Census spool commit failed")
            } finally {
                tmp.delete()
            }
            stats.spooled.incrementAndGet()
            trim()
        }
    }

    open suspend fun take(maxItems: Int, maxBytes: Int): List<Spooled> = withContext(io) {
        require(maxItems > 0 && maxBytes > 0)
        mutex.withLock {
            initialize()
            val pending = readInFlight()
            if (pending != null) {
                // A retry owns its original set and order, regardless of new arrivals or limits.
                val items = pending.ids.mapNotNull { id ->
                    val file = File(directory, id)
                    if (file.isFile) readOrDrop(file) else null
                }
                if (items.isNotEmpty()) return@withLock items
                deleteInFlight()
            }
            val result = mutableListOf<Spooled>()
            var bytes = 0
            for (file in files()) {
                if (result.size == maxItems) break
                val item = readOrDrop(file) ?: continue
                val itemBytes = item.itemJson.toByteArray(Charsets.UTF_8).size
                if (itemBytes > maxBytes) {
                    drop(file, stats.spoolOversized)
                    continue
                }
                if (bytes + itemBytes > maxBytes) break
                result += item
                bytes += itemBytes
            }
            result
        }
    }

    open suspend fun inFlight(): InFlight? = withContext(io) {
        mutex.withLock {
            initialize()
            readInFlight()
        }
    }

    open suspend fun markInFlight(ids: Collection<String>, batchId: String) = withContext(io) {
        mutex.withLock {
            initialize()
            require(ids.isNotEmpty() && ids.all { FILE_NAME.matches(it) })
            val json = JsonObject(mapOf(
                "batchId" to JsonPrimitive(batchId),
                "ids" to JsonArray(ids.map { JsonPrimitive(it) }),
            ))
            val tmp = File(inFlightFile.parentFile, "inflight.json.tmp")
            try {
                FileOutputStream(tmp).use {
                    it.write(json.toString().toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                if (!tmp.renameTo(inFlightFile)) throw IOException("Census in-flight commit failed")
            } finally {
                tmp.delete()
            }
        }
    }

    open suspend fun clearInFlight() = withContext(io) {
        mutex.withLock { deleteInFlight() }
    }

    private fun readInFlight(): InFlight? {
        if (!inFlightFile.exists()) return null
        return runCatching {
            val json = Json.parseToJsonElement(inFlightFile.readText(Charsets.UTF_8)).jsonObject
            val ids = json.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
            require(ids.isNotEmpty() && ids.all { FILE_NAME.matches(it) })
            InFlight(json.getValue("batchId").jsonPrimitive.content, ids)
        }.getOrElse {
            deleteInFlight()
            stats.inflightCorrupt.incrementAndGet()
            null
        }
    }

    private fun deleteInFlight() {
        if (inFlightFile.exists() && !inFlightFile.delete()) throw IOException("Census in-flight removal failed")
    }

    open suspend fun remove(ids: Collection<String>) = withContext(io) {
        mutex.withLock {
            initialize()
            ids.forEach { id ->
                require(FILE_NAME.matches(id))
                val file = File(directory, id)
                if (file.exists() && !file.delete()) throw IOException("Census spool removal failed")
            }
            size.value = files().size
        }
    }

    /** Identity reset (#1185): deletes every queued record, the in-flight marker and any temp file. Not counted as a drop. */
    open suspend fun clear() = withContext(io) {
        mutex.withLock {
            initialize()
            directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                if (!file.delete()) throw IOException("Census spool clear failed")
            }
            deleteInFlight()
            size.value = 0
        }
    }

    private fun initialize() {
        if (initialized) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Census spool unavailable")
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        sequence = files().maxOfOrNull { it.nameWithoutExtension.substringAfter('-').toLong() } ?: 0L
        trim()
        initialized = true
    }

    private fun files(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && FILE_NAME.matches(it.name) }
        .sortedWith(compareBy<File> { it.name.substringBefore('-').toLong() }
            .thenBy { it.nameWithoutExtension.substringAfter('-').toLong() })

    private fun trim() {
        val files = files()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        size.value = count
        for (file in files) {
            if (count <= maxFiles && bytes <= maxDiskBytes) break
            val length = file.length()
            drop(file)
            count--
            bytes -= length
        }
        size.value = count
    }

    private fun drop(file: File, reason: java.util.concurrent.atomic.AtomicLong? = null) {
        if (!file.delete()) throw IOException("Census spool drop failed")
        stats.spoolDropped.incrementAndGet()
        reason?.incrementAndGet()
        size.update { it - 1 }
    }

    private fun readOrDrop(file: File): Spooled? = try {
        read(file)
    } catch (_: IllegalArgumentException) {
        drop(file, stats.spoolCorrupt)
        null
    }

    private fun read(file: File): Spooled {
        val text = file.readText(Charsets.UTF_8)
        val wrapper = Json.parseToJsonElement(text).jsonObject
        require(wrapper.keys == setOf("platform", "fingerprint", "skeleton", "captureId"))
        val start = text.indexOf("\"skeleton\":") + "\"skeleton\":".length
        val end = text.lastIndexOf(",\"captureId\":")
        require(start >= "\"skeleton\":".length && end > start)
        val json = text.substring(start, end)
        val skeleton = SkeletonSchema.deserialize(json)
        val fingerprint = wrapper.getValue("fingerprint").jsonPrimitive.content
        require(skeleton.fingerprint == fingerprint)
        require(skeleton.platform == wrapper.getValue("platform").jsonPrimitive.content)
        val capture = wrapper.getValue("captureId")
        require(capture == JsonNull || (capture.jsonPrimitive.isString &&
            UUID.fromString(capture.jsonPrimitive.content).toString() == capture.jsonPrimitive.content))
        return Spooled(file.name, fingerprint, json)
    }

    private companion object {
        val FILE_NAME = Regex("[0-9]{1,19}-[0-9]{1,19}\\.json")
    }
}
