package cloud.trotter.dashbuddy.core.data.capture

import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Disk-based capture bus. Writes pre-serialized [cloud.trotter.dashbuddy.domain.capture.CaptureEnvelope] JSON to
 * the external files directory for debugging and regression-test corpus building.
 *
 * Layout: `captures/PLATFORM/SOURCE/CLASSIFICATION/TIMESTAMP__PLATFORM__SOURCE__CLASSIFICATION__HASH.json`
 *
 * Deduplicates by contentHash per bucket — only the first occurrence of a
 * structurally unique payload is saved per session.
 */
@Singleton
class DiskCaptureBus @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) : CaptureBus {

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    // DateTimeFormatter is thread-safe (#364) — the old shared SimpleDateFormat
    // could garble filenames when capture coroutines overlapped.
    private val timestampFormat = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    /** Structural hash set per bucket. Prevents duplicate writes within a session. */
    private val seenHashes = ConcurrentHashMap<String, MutableSet<Int>>()

    private val baseDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "captures")
    }

    override fun offer(
        captureId: String,
        source: String,
        classification: String?,
        platform: String,
        envelopeJson: String,
        contentHash: Int?,
    ): String? {
        val sub = classification ?: "UNKNOWN"
        val bucket = "$platform/$source/$sub"

        // Dedup: skip if we've already saved this structural hash for this bucket
        if (contentHash != null) {
            val seen = seenHashes.getOrPut(bucket) { ConcurrentHashMap.newKeySet() }
            if (!seen.add(contentHash)) return null
        }

        scope.launch {
            try {
                val dir = File(baseDir, bucket)
                dir.mkdirs()

                val hashHex = (contentHash ?: envelopeJson.hashCode()).toString(16).takeLast(6)
                val ts = timestampFormat.format(Instant.now())
                val fileName = "${ts}__${platform}__${source}__${sub}__$hashHex.json"

                File(dir, fileName).writeText(envelopeJson)
                Timber.d("Captured: %s/%s (captureId=%s)", bucket, fileName, captureId)
            } catch (e: Exception) {
                Timber.e(e, "Capture write failed: %s", bucket)
            }
        }

        return captureId
    }
}
