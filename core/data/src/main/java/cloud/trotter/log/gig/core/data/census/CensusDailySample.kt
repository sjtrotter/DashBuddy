package cloud.trotter.log.gig.core.data.census

import android.content.Context
import cloud.trotter.log.gig.domain.census.CensusSampleKey
import cloud.trotter.log.gig.domain.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1620 (ADR-0011 §8): one skeleton per variant ([CensusSampleKey]) per UTC day. The first sighting of a variant on a
 * day is kept; later ones only add to a count in `filesDir/census/sampled.json`, which never leaves the phone and is
 * outside every backup (the whole `census/` directory is). The day is the skeleton's own UTC `day`.
 *
 * Only the two latest days are kept, so the file stays small; a skeleton for an older day than those is not kept.
 */
@Singleton
class CensusDailySample internal constructor(
    private val file: File,
    private val io: CoroutineDispatcher,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(File(context.filesDir, "census/sampled.json"), io)

    private val mutex = Mutex()
    /** day → variant key → sightings. */
    private var days: Map<String, Map<String, Int>>? = null

    /** True the first time [key] is seen on [day]; a later sighting that day counts locally and returns false. */
    suspend fun admit(day: String, key: String): Boolean = withContext(io) {
        mutex.withLock {
            val current = days ?: load()
            if (current.size >= KEPT_DAYS && day !in current && day < current.keys.min()) return@withLock false
            val counts = current[day].orEmpty()
            val seen = counts[key]
            val next = (current + (day to counts + (key to ((seen ?: 0) + 1).coerceAtMost(MAX_COUNT)))).toSortedMap()
            while (next.size > KEPT_DAYS) next.remove(next.firstKey())
            days = next
            save(next)
            seen == null
        }
    }

    private fun load(): Map<String, Map<String, Int>> = runCatching {
        if (!file.isFile) emptyMap() else Json.decodeFromString<Map<String, Map<String, Int>>>(file.readText(Charsets.UTF_8))
    }.getOrDefault(emptyMap())

    private fun save(value: Map<String, Map<String, Int>>) {
        file.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Census sample unavailable") }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(Json.encodeToString(value), Charsets.UTF_8)
            if (!tmp.renameTo(file)) throw IOException("Census sample commit failed")
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val KEPT_DAYS = 2
        const val MAX_COUNT = 1_000_000
    }
}
