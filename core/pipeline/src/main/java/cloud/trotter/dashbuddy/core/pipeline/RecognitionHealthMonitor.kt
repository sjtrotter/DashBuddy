package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The side-effecting half of the #937 recognition-health alarm: one WARN plus one
 * dasher-visible notice the first time a platform's recognition collapses in this process.
 *
 * The decision is pure and lives in [RecognitionHealth]; this class owns only the edges — the
 * package→platform resolution, the lock, the log line and the reporter call.
 *
 * **Fail-open, absolutely.** This is called from inside the sensing flow, one call per admitted
 * frame. Every path is wrapped in `catch (Throwable)` (only `CancellationException` rethrown —
 * the #909 rule), because a health diagnostic that can kill the pipeline would be the very
 * failure it exists to detect.
 *
 * **Scope of "once".** The edge gate is per platform per PROCESS. Per-*dash* would be the
 * ideal grain, but dash lifecycle lives in `:core:state`, which depends on `:core:pipeline` and
 * not the reverse — reaching for it would invert the module graph for a diagnostic. A process
 * restart re-arms the alarm, which is the right bias: recognition breakage is per-dash news,
 * not a once-per-install disclosure like #938's locale notice.
 *
 * **Platform-agnostic (principle 8).** Frames are keyed by `Platform.wire` via the registry;
 * there is no platform literal here and nothing is tuned to whichever platform we field-test
 * most. A frame whose package resolves to `Unknown` is ignored — an unattributable frame has no
 * recognition contract to be measured against.
 */
@Singleton
class RecognitionHealthMonitor @Inject constructor(
    private val reporter: RecognitionHealthReporter,
) {

    private val lock = Any()
    private val health = RecognitionHealth()

    /**
     * Record one frame that survived `FrameGate` admission.
     *
     * @param packageName the window's real package (the frame's own attribution).
     * @param unknown true when the frame classified UNKNOWN.
     */
    fun onScreenAdmitted(packageName: String?, unknown: Boolean) {
        try {
            val platform = Platform.fromPackage(packageName)
            if (platform == Platform.Unknown) return

            val alarm = synchronized(lock) { health.record(platform.wire, unknown) } ?: return

            // WARN, not ERROR: a defended invariant fired and the app is degraded, but nothing
            // is lost or crashed (principle 7). PII-free by construction — a platform wire and
            // a percentage, no screen text.
            Timber.tag(TAG).w(
                "Recognition health: %d%% of the last %d %s frames classified UNKNOWN — " +
                    "anchors may be broken by a platform app update (#937)",
                alarm.unknownPercent,
                RecognitionHealth.WINDOW_SIZE,
                alarm.platformWire,
            )
            reporter.onRecognitionDegraded(alarm.platformWire, alarm.unknownPercent)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Recognition health check failed — sensing unaffected (#937)")
        }
    }

    private companion object {
        const val TAG = "RecognitionHealth"
    }
}
