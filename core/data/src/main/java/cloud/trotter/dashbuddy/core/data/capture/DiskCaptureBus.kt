package cloud.trotter.dashbuddy.core.data.capture

import android.content.Context
import cloud.trotter.dashbuddy.core.database.log.dto.SnapshotWrapperDto
import cloud.trotter.dashbuddy.core.database.log.mapper.toDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-based capture bus. Writes UI hierarchy snapshots to the external
 * files directory for debugging and regression-test corpus building.
 *
 * Layout: captures/PLATFORM/TYPE/SUBTYPE/TIMESTAMP__PLATFORM__TYPE__SUBTYPE__HASH.json
 *
 * Where:
 * - PLATFORM = "doordash" (hardcoded until multi-platform)
 * - TYPE     = pipelineId (e.g. "accessibility.window")
 * - SUBTYPE  = classification / ruleId (e.g. "MAIN_MAP_IDLE")
 *
 * Deduplicates by structural hash per bucket — only the first
 * occurrence of a structurally unique tree is saved per session.
 */
@Singleton
class DiskCaptureBus @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureBus {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { prettyPrint = true }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)

    /** structural hash set per bucket. Prevents duplicate writes within a session. */
    private val seenHashes = ConcurrentHashMap<String, MutableSet<Int>>()

    private val baseDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "captures")
    }

    override fun <T> offer(pipelineId: String, payload: T, ruleId: String?) {
        scope.launch {
            try {
                when (payload) {
                    is UiNode -> saveUiNode(PLATFORM, pipelineId, payload, ruleId)
                    else -> Timber.v("Capture: unsupported payload type for %s", pipelineId)
                }
            } catch (e: Exception) {
                Timber.e(e, "Capture write failed: %s/%s", pipelineId, ruleId)
            }
        }
    }

    private fun saveUiNode(platform: String, type: String, node: UiNode, subtype: String?) {
        val sub = subtype ?: "UNKNOWN"
        val hash = node.structuralHash

        // Dedup: skip if we've already saved this structural hash for this bucket
        val bucket = "$platform/$type/$sub"
        val seen = seenHashes.getOrPut(bucket) { ConcurrentHashMap.newKeySet() }
        if (!seen.add(hash)) return

        val dir = File(baseDir, bucket)
        dir.mkdirs()

        val dto = node.toDto()
        val wrapper = SnapshotWrapperDto(
            timestamp = System.currentTimeMillis(),
            root = dto,
        )

        val hashHex = hash.toString(16).takeLast(6)
        val ts = timestampFormat.format(Date())
        val fileName = "${ts}__${platform}__${type}__${sub}__$hashHex.json"

        File(dir, fileName).writeText(json.encodeToString(wrapper))
        Timber.d("Captured: %s/%s", bucket, fileName)
    }

    companion object {
        private const val PLATFORM = "doordash"
    }
}
