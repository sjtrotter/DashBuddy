package cloud.trotter.log.gig.core.data.census.contributor

import android.content.Context
import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.contributor.ContributorSlots
import cloud.trotter.census.contract.contributor.ContributorVariant
import cloud.trotter.census.contract.sha256OrNull
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.contributor.ContributorGate
import cloud.trotter.log.gig.domain.census.contributor.ContributorReviewIntake
import cloud.trotter.log.gig.domain.census.contributor.PendingContributorReviews
import cloud.trotter.log.gig.domain.census.contributor.ReviewWords
import cloud.trotter.log.gig.domain.census.contributor.UploadedSkeletons
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.di.IoDispatcher
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * #1626: one screen waiting for review. Everything here is the sanitized outbound form: [skeletonJson] is the skeleton
 * item exactly as it went to the upload spool (hashes, coarse kinds, shapes and `withheld`, never screen text), and
 * [layout] is its census layout id. [uploadedDay] is the UTC day the server first stored it (null until then); the
 * classes the review needs come from the day after (census#81's daily release).
 */
@Serializable
data class ReviewCandidate(
    val id: String,
    val skeletonJson: String,
    val itemSha256: String,
    val layout: String,
    /** The skeleton's own UTC day. */
    val day: String,
    val uploadedDay: String? = null,
) {
    /** Reviewable on [todayUtc]: uploaded on an earlier UTC day, so today's class release covers it. */
    fun readyOn(todayUtc: LocalDate): Boolean = uploadedDay?.let { runCatching { LocalDate.parse(it) < todayUtc }.getOrNull() } == true

    override fun toString(): String = "ReviewCandidate($id, uploaded=${uploadedDay != null})"
}

@Serializable
private data class QueueFile(
    val entries: List<ReviewCandidate> = emptyList(),
    /** Layout ids already reviewed or deleted, with the UTC day, so a layout is offered once a day at most. */
    val done: List<DoneLayout> = emptyList(),
)

@Serializable
private data class DoneLayout(val layout: String, val day: String)

/**
 * #1626: the screens waiting for review (ADR-0011 §15). Takes a candidate from the census sink only after its skeleton
 * went to the upload spool and only while the contributor gate stands ([ContributorGate.mayUpload], checked on each
 * offer): at most [MAX_PER_DAY] new layouts a UTC day, one entry per layout, [MAX_ENTRIES] in all, dropped after
 * [KEEP_DAYS] days.
 *
 * **Two halves.** The sanitized candidate ([ReviewCandidate]) is stored in `filesDir/census/review/queue.json`,
 * inside the census directory every backup and transfer excludes. The words the review may show ([ReviewWords]) and
 * the confirmed words waiting for their gate ([held]) live only in this object's memory: never written, so a process
 * death discards them, and the review then has no word to show for that screen.
 *
 * It is the [PendingContributorReviews] the contributor clears whenever the grant ends: [clear] empties both halves
 * and deletes the file, and throws when it cannot, so the contributor runs it again.
 */
@Singleton
class ContributorReviewQueue internal constructor(
    private val file: File,
    private val gate: () -> ContributorGate,
    grantStored: Flow<Boolean>,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
) : PendingContributorReviews, ContributorReviewIntake, UploadedSkeletons {
    @Inject constructor(
        @ApplicationContext context: Context,
        gate: Lazy<ContributorGate>,
        record: ContributorDataSource,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
        clock: Clock,
    ) : this(
        File(context.filesDir, "census/review/queue.json"),
        { gate.get() },
        record.record.map { it.grant != null },
        scope,
        io,
        clock,
    )

    private val mutex = Mutex()
    private val state = MutableStateFlow(QueueFile())
    @Volatile private var loaded = false
    private val words = HashMap<String, ReviewWords>()
    private val heldWords = LinkedHashMap<ContributorVariant, MutableList<HeldWord>>()

    /** A confirmed word waiting for its gate: in memory only. */
    class HeldWord(val variant: ContributorVariant, val slot: String, val hash: String, val word: String) {
        override fun toString(): String = "HeldWord($slot, [redacted])"
    }

    private val grant: StateFlow<Boolean> = grantStored
        .catch { emit(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Every stored candidate, oldest first. Loads the store on first collection. */
    val entries: Flow<List<ReviewCandidate>> = state.asStateFlow()
        .map { it.entries }
        .onStart { load() }

    override val wantsCandidates: Boolean
        get() = grant.value && state.value.entries.count { it.day == todayUtc().toString() } < MAX_PER_DAY

    /** The words held for [id], or null when none are (never collected, or lost to a process death). */
    suspend fun wordsFor(id: String): ReviewWords? = mutex.withLock { words[id] }

    suspend fun candidate(id: String): ReviewCandidate? {
        load()
        return state.value.entries.firstOrNull { it.id == id }
    }

    override suspend fun offer(record: CensusRecord) {
        val reviewWords = record.reviewWords ?: return
        try {
            val item = CensusSkeletonSchema.deserialize(record.skeletonJson) as? UiSkeletonDto ?: return
            if (item.schemaId != SkeletonSchema.SCHEMA_ID_V2) return
            val layout = ContributorSlots.layout(item) ?: return
            val sha = sha256OrNull(record.skeletonJson) ?: return
            // The gate is checked first and outside this queue's lock: a gate that ends the grant clears this queue.
            if (!gate().mayUpload()) return
            load()
            mutex.withLock {
                val current = pruned(state.value)
                val today = todayUtc().toString()
                val admitted = current.entries.none { it.layout == layout } &&
                    current.done.none { it.layout == layout && it.day == today } &&
                    current.entries.count { it.day == item.day } < MAX_PER_DAY
                if (!admitted) return@withLock
                val candidate = ReviewCandidate(UUID.randomUUID().toString(), record.skeletonJson, sha, layout, item.day)
                val kept = (current.entries + candidate).takeLast(MAX_ENTRIES)
                save(current.copy(entries = kept))
                words.keys.retainAll(kept.map { it.id }.toSet())
                words[candidate.id] = reviewWords
                Timber.tag(TAG).d("census review candidate kept waiting=%d", kept.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("census review candidate dropped cause=%s", e.javaClass.simpleName)
        }
    }

    override suspend fun uploaded(itemsJson: List<String>) {
        if (itemsJson.isEmpty()) return
        load()
        val shas = itemsJson.mapNotNull { sha256OrNull(it) }.toSet()
        mutex.withLock {
            val current = state.value
            if (current.entries.none { it.uploadedDay == null && it.itemSha256 in shas }) return@withLock
            val today = todayUtc().toString()
            save(current.copy(entries = current.entries.map {
                if (it.uploadedDay == null && it.itemSha256 in shas) it.copy(uploadedDay = today) else it
            }))
        }
    }

    /** Sent or deleted: the candidate and its words go; its layout is not offered again today. */
    suspend fun remove(id: String) {
        load()
        mutex.withLock {
            val current = state.value
            val entry = current.entries.firstOrNull { it.id == id }
            words.remove(id)
            if (entry == null) return@withLock
            val today = todayUtc().toString()
            save(pruned(current.copy(
                entries = current.entries.filterNot { it.id == id },
                done = current.done + DoneLayout(entry.layout, today),
            )))
        }
    }

    /** Holds a confirmed word until its gate opens; memory only. */
    suspend fun hold(word: HeldWord) = mutex.withLock {
        val list = heldWords.getOrPut(word.variant) { mutableListOf() }
        if (list.none { it.slot == word.slot && it.hash == word.hash }) list += word
        if (list.size > MAX_HELD_PER_VARIANT) list.removeAt(0)
        while (heldWords.size > MAX_HELD_VARIANTS) heldWords.remove(heldWords.keys.first())
    }

    /** Takes every held word out, for a gate check; the caller holds back again what stays closed. */
    suspend fun takeHeld(): List<HeldWord> = mutex.withLock {
        heldWords.values.flatten().also { heldWords.clear() }
    }

    /** [PendingContributorReviews]: the grant ended. Both halves go; a file that cannot be deleted throws. */
    override suspend fun clear() {
        mutex.withLock {
            words.clear()
            heldWords.clear()
            state.value = QueueFile()
            loaded = true
            withContext(io) {
                if (file.exists() && !file.delete()) throw IOException("census review queue clear failed")
                File(file.parentFile, "${file.name}.tmp").delete()
            }
        }
    }

    private suspend fun load() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            state.value = withContext(io) {
                try {
                    if (file.isFile) json.decodeFromString(QueueFile.serializer(), file.readText(Charsets.UTF_8)) else QueueFile()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    file.delete()
                    QueueFile()
                }
            }
            loaded = true
        }
    }

    /** Writes [next] whole (temp file, then rename) and publishes it. Caller holds [mutex]. */
    private suspend fun save(next: QueueFile) {
        withContext(io) {
            val dir = file.parentFile ?: throw IOException("census review directory unavailable")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("census review directory unavailable")
            if (next.entries.isEmpty() && next.done.isEmpty()) {
                if (file.exists() && !file.delete()) throw IOException("census review queue write failed")
            } else {
                val tmp = File(dir, "${file.name}.tmp")
                try {
                    FileOutputStream(tmp).use {
                        it.write(json.encodeToString(QueueFile.serializer(), next).toByteArray(Charsets.UTF_8))
                        it.fd.sync()
                    }
                    if (!tmp.renameTo(file)) throw IOException("census review queue write failed")
                } finally {
                    tmp.delete()
                }
            }
        }
        state.value = next
    }

    private fun pruned(current: QueueFile): QueueFile {
        val today = todayUtc()
        val oldest = today.minusDays(KEEP_DAYS)
        fun recent(day: String) = runCatching { !LocalDate.parse(day).isBefore(oldest) }.getOrDefault(false)
        return current.copy(
            entries = current.entries.filter { recent(it.day) },
            done = current.done.filter { it.day == today.toString() },
        )
    }

    fun todayUtc(): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(ZoneOffset.UTC).toLocalDate()

    companion object {
        private const val TAG = "Census"

        /** New layouts kept per UTC day, and the reviews offered a day. */
        const val MAX_PER_DAY: Int = 3
        const val MAX_ENTRIES: Int = 6
        /** The server takes skeletons up to 7 days old; a candidate older than that is dropped. */
        const val KEEP_DAYS: Long = 7
        private const val MAX_HELD_PER_VARIANT = 16
        private const val MAX_HELD_VARIANTS = 6

        private val json = Json { ignoreUnknownKeys = false }
    }
}
