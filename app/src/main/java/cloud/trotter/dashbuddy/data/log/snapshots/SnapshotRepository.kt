package cloud.trotter.dashbuddy.data.log.snapshots

import android.content.Context
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val snapshotDao: SnapshotDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pretty timestamp for filenames: "2026-02-02_12-30-05"
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // Configuration
    private val defaultLimit = 10
    private val unknownLimit = 50

    // JSON Serializer (Assuming you have kotlinx.serialization setup, otherwise Gson)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val snapshotDir by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "snapshots").apply { mkdirs() }
    }

    /**
     * New Entry Point: Accepts breadcrumbs
     */
    fun saveSnapshot(node: UiNode, screenType: String, breadcrumbs: List<String>) {
        scope.launch {
            try {
                val hash = node.structuralHash

                // 1. Deduplication (Same logic as before)
                val existing = snapshotDao.findByHash(screenType, hash)
                if (existing != null) {
                    snapshotDao.updateTimestamp(existing.id, System.currentTimeMillis())
                    Timber.d("Duplicate snapshot ignored: $screenType / ${Integer.toHexString(hash)}")
                    return@launch
                }

                // 2. Enforce Quota
                enforceQuota(screenType)

                // 3. Save Wrapper
                writeToDiskAndDb(node, screenType, hash, breadcrumbs)

            } catch (e: Exception) {
                Timber.e(e, "Failed to save snapshot for $screenType")
            }
        }
    }

    private suspend fun enforceQuota(screenType: String) {
        val limit = if (screenType == "UNKNOWN") unknownLimit else defaultLimit
        val count = snapshotDao.getCountByType(screenType)

        if (count >= limit) {
            val oldest = snapshotDao.getOldestByType(screenType)
            if (oldest != null) {
                // TODO: In the future, check if oldest.isGolden before deleting?
                // For on-device logs, we probably don't care about Golden (that's for tests),
                // so we just prune to save space.
                File(oldest.filePath).delete()
                snapshotDao.delete(oldest)
                Timber.d("♻️ Pruned oldest variation for $screenType")
            }
        }
    }

    private suspend fun writeToDiskAndDb(
        node: UiNode,
        screenType: String,
        hash: Int,
        breadcrumbs: List<String>
    ) {
        val typeDir = File(snapshotDir, screenType).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val timeString = fileDateFormat.format(Date(now))
        val hashString = Integer.toHexString(hash)

        // NEW FILENAME: 2026-02-02_12-30-05__a1b2c3.json
        // Sorts chronologically in Finder/Explorer!
        val filename = "${timeString}__${screenType}__${hashString}.json"
        val file = File(typeDir, filename)

        // Create Wrapper
        val wrapper = SnapshotWrapper(
            timestamp = now,
            breadcrumbs = breadcrumbs,
            isGolden = false, // Default is false for live captures
            root = node
        )

        // Write
        file.writeText(json.encodeToString(wrapper))

        // Save Record
        val record = SnapshotRecord(
            screenType = screenType,
            structuralHash = hash,
            contentHash = node.contentHash,
            filePath = file.absolutePath,
            timestamp = now
        )
        snapshotDao.insert(record)

        Timber.i("📸 Saved Snapshot: $screenType/$filename")
    }
}