package cloud.trotter.dashbuddy.core.pipeline

import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the `versionName` of an OBSERVED third-party app (#937).
 *
 * Recognition anchors break when a platform ships an app update, and until #937 nothing on
 * the device recorded which version produced a given frame — so a desk pull could see the
 * breakage but never correlate it with the release that caused it. Every classified
 * observation now carries the stamp in its `ReplayMetadata`.
 */
fun interface PlatformAppVersions {

    /** The package's `versionName`, or null when it can't be resolved. Never throws. */
    fun versionName(packageName: String): String?

    companion object {
        /** The no-op resolver — nothing is ever stamped. For tests that don't exercise #937. */
        val NONE = PlatformAppVersions { null }
    }
}

/**
 * The production [PlatformAppVersions]: one lookup per package per process, cached.
 *
 * **Caching decision (#937):** the cache is never invalidated on a package update. An app
 * update kills the updated app's process and the accessibility service is re-bound, and in
 * practice DashBuddy's own process is restarted alongside it; the residual — one stale stamp
 * on the frames between an in-place update and the next process start — is strictly smaller
 * than the cost of listening for `PACKAGE_REPLACED` broadcasts on the sensing hot path. The
 * stamp is a diagnostic, not an input to any decision, so a stale value is a nuisance, never
 * a correctness problem.
 *
 * **Negative results are cached too.** A package that doesn't resolve (uninstalled, an OEM
 * overlay, a `NameNotFoundException`) would otherwise pay a binder round-trip on EVERY frame.
 *
 * **Fail-open by construction** (#909's lesson): the lookup is wrapped in `catch (Throwable)`
 * with only `CancellationException` rethrown — a diagnostic must never be able to kill a
 * frame, and a `PackageManager` call crosses a process boundary.
 *
 * @param lookup the raw resolution (the `PackageManager` call at the DI edge). Injected as a
 *   lambda so the caching/fail-open logic above is unit-testable without Robolectric.
 * @param stats records each FIRST resolution for the periodic summary line. The cache here is
 *   the SSOT of resolution; [PipelineStats] holds only a render-only copy for the log line.
 */
class CachingPlatformAppVersions(
    private val lookup: (String) -> String?,
    private val stats: PipelineStats,
) : PlatformAppVersions {

    /** `packageName → versionName`, with [ABSENT] standing in for "resolved to nothing". */
    private val cache = ConcurrentHashMap<String, String>()

    override fun versionName(packageName: String): String? {
        if (packageName.isEmpty()) return null
        cache[packageName]?.let { return it.takeIf { v -> v != ABSENT } }

        val resolved = try {
            lookup(packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "versionName lookup failed for a package — leaving it unstamped (#937)")
            null
        }

        // Bounded: the observed-package set is small (the enabled platforms plus whatever the
        // dasher's screen shows), but this is fed from untrusted frame data, so cap it rather
        // than trust that. Past the cap we still ANSWER, we just stop remembering — correctness
        // is unchanged, only the binder traffic.
        if (cache.size < MAX_CACHED_PACKAGES) {
            cache[packageName] = resolved ?: ABSENT
            if (resolved != null) stats.onPlatformAppVersion(packageName, resolved)
        }
        return resolved
    }

    companion object {
        private const val TAG = "Pipeline"

        /** Sentinel for a negative lookup — `ConcurrentHashMap` cannot hold a null value. */
        private const val ABSENT = ""

        /** Cache cap; see [versionName] for why exceeding it is not an error. */
        const val MAX_CACHED_PACKAGES = 64
    }
}
