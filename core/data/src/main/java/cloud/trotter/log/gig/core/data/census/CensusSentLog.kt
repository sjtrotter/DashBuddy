package cloud.trotter.log.gig.core.data.census

import android.content.Context
import cloud.trotter.log.gig.domain.census.CensusSentBatch
import cloud.trotter.log.gig.domain.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1622: the phone's record of what the census sent, for "What gig.log sent". The transport hands it the exact bytes
 * of each request the server read (bound as the transport's `CensusSentObserver`); it keeps only the summary [CensusSentBatch.read] makes of
 * them (kinds, delivery apps, counts; no hash or value), for [MAX_AGE_MILLIS] and at most [MAX_BATCHES] requests.
 * The file is under `census/`, which Android backup and device transfer leave out, and it is never uploaded.
 */
@Singleton
class CensusSentLog internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
    private val now: () -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher) :
        this(File(context.filesDir, "census/sent.json"), io, System::currentTimeMillis)

    private val mutex = Mutex()
    private val state = MutableStateFlow<List<CensusSentBatch>?>(null)

    /** Newest first. Fail-open: an unreadable file reads as empty, never as an error on the screen. */
    val batches: Flow<List<CensusSentBatch>> = flow {
        withContext(io) { mutex.withLock { load() } }
        emitAll(state.filterNotNull())
    }

    /** Records one request body the server read, summarized; a body that carries no census data is ignored. */
    suspend fun record(path: String, body: ByteArray) {
        val batch = CensusSentBatch.read(path, body, now()) ?: return
        withContext(io) {
            mutex.withLock {
                write(listOf(batch) + load())
            }
        }
    }

    /** Forgets every sent record (the on-phone wipe, #1579). */
    suspend fun clear() = withContext(io) {
        mutex.withLock {
            if (file.exists() && !file.delete()) throw IOException("Census sent record removal failed")
            state.value = emptyList()
        }
    }

    /**
     * The kept records, pruned to [MAX_AGE_MILLIS]: an expired record is dropped from the file and from [batches] on
     * every read (each collection of [batches] reads first), not only on the next write. A failed rewrite keeps the
     * pruned view; the next read or write tries again.
     */
    private fun load(): List<CensusSentBatch> {
        val loaded = state.value ?: runCatching {
            if (file.isFile) JSON.decodeFromString(SERIALIZER, file.readText(Charsets.UTF_8)) else emptyList()
        }.getOrElse { emptyList() }
        val kept = pruned(loaded)
        if (kept.size != loaded.size) runCatching { write(kept) }
        state.value = kept
        return kept
    }

    private fun pruned(batches: List<CensusSentBatch>): List<CensusSentBatch> {
        val oldest = now() - MAX_AGE_MILLIS
        return batches.filter { it.sentAtMillis >= oldest }.sortedByDescending { it.sentAtMillis }.take(MAX_BATCHES)
    }

    private fun write(batches: List<CensusSentBatch>) {
        val kept = pruned(batches)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("Census sent record unavailable")
        val tmp = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(JSON.encodeToString(SERIALIZER, kept).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("Census sent record commit failed")
        } finally {
            tmp.delete()
        }
        state.value = kept
    }

    companion object {
        const val MAX_AGE_MILLIS: Long = CensusSentBatch.KEEP_MILLIS
        const val MAX_BATCHES: Int = 60
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(CensusSentBatch.serializer())
    }
}
