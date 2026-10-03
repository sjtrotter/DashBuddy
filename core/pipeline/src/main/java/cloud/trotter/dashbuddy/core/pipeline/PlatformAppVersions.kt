package cloud.trotter.dashbuddy.core.pipeline

import kotlinx.coroutines.CancellationException
import timber.log.Timber

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
 * The production [PlatformAppVersions]: one lookup per package per cache lifetime.
 *
 * **Caching decision (#937, #1197):** the stamp now drives the daily health rollup, so a
 * stale version after an in-place update is bounded to ten minutes. A `PACKAGE_REPLACED`
 * receiver was rejected as a sensing-hot-path cost; positive and negative resolutions
 * instead expire together after the TTL.
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
 * @param stats records each successful resolution for the periodic summary line. The cache here is
 *   the SSOT of resolution; [PipelineStats] holds only a render-only copy for the log line.
 * @param ttlMillis bounds stale resolutions, including negative results.
 * @param clock supplies resolution timestamps in milliseconds.
 */
class CachingPlatformAppVersions(
    private val lookup: (String) -> String?,
    private val stats: PipelineStats,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlatformAppVersions {

    private data class Resolution(val value: String?, val resolvedAt: Long)
    private val cache = mutableMapOf<String, Resolution>()

    @Synchronized
    override fun versionName(packageName: String): String? {
        if (packageName.isEmpty()) return null
        val now = clock()
        cache[packageName]?.let { if (now - it.resolvedAt in 0 until ttlMillis) return it.value }

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
        if (packageName in cache || cache.size < MAX_CACHED_PACKAGES) {
            cache[packageName] = Resolution(resolved, clock())
            if (resolved != null) stats.onPlatformAppVersion(packageName, resolved)
        }
        return resolved
    }

    companion object {
        private const val TAG = "Pipeline"

        /** Cache cap; see [versionName] for why exceeding it is not an error. */
        const val MAX_CACHED_PACKAGES = 64
    }
}
