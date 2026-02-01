package cloud.trotter.dashbuddy.data.log.snapshots

import android.content.Context
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val snapshotDao: SnapshotDao
) {
    // 1. Scope: Run on IO thread, SupervisorJob prevents crashes from cancelling the scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 2. Base Directory: /storage/.../Android/data/cloud.trotter.dashbuddy/files/snapshots/
    private val snapshotDir by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "snapshots").apply { mkdirs() }
    }

    // --- Configuration ---
    private val defaultLimit = 10
    private val unknownLimit = 50

    /**
     * Entry point. Checks for uniqueness before saving.
     */
    fun saveSnapshot(node: UiNode, screenType: String) {
        scope.launch {
            try {
                val hash = node.structuralHash

                // 1. Deduplication Check
                val existing = snapshotDao.findByHash(screenType, hash)

                if (existing != null) {
                    // DUPLICATE: Update timestamp to mark it as "fresh" (recently seen)
                    snapshotDao.updateTimestamp(existing.id, System.currentTimeMillis())
                    Timber.d(
                        "Duplicate snapshot ignored (touched timestamp): $screenType / ${
                            Integer.toHexString(
                                hash
                            )
                        }"
                    )
                    return@launch
                }

                // 2. New Variation: Enforce Quota (Delete oldest if full)
                enforceQuota(screenType)

                // 3. Save to Disk and DB
                writeToDiskAndDb(node, screenType, hash)

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
                // Delete the physical file
                val file = File(oldest.filePath)
                if (file.exists()) {
                    file.delete()
                }
                // Delete the DB record
                snapshotDao.delete(oldest)
                Timber.d("♻️ Pruned oldest variation for $screenType: ${oldest.filePath}")
            }
        }
    }

    private suspend fun writeToDiskAndDb(node: UiNode, screenType: String, hash: Int) {
        // Create Subfolder: /snapshots/OFFER/
        val typeDir = File(snapshotDir, screenType)
        if (!typeDir.exists()) {
            typeDir.mkdirs()
        }

        // Filename: A1B2C3.json
        val filename = "${Integer.toHexString(hash)}.json"
        val file = File(typeDir, filename)

        // Write content
        file.writeText(node.toJson())

        // Save Record
        val record = SnapshotRecord(
            screenType = screenType,
            structuralHash = hash,
            contentHash = node.contentHash,
            filePath = file.absolutePath, // <--- Correctly accessible now
            timestamp = System.currentTimeMillis()
        )
        snapshotDao.insert(record)

        Timber.i("📸 Saved NEW Snapshot: $screenType/${file.name}")
    }
}