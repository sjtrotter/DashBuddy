package cloud.trotter.dashbuddy.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #937 — the caching/fail-open half of the observed-app version stamp. The `PackageManager`
 * call itself is injected as a lambda, so everything that could actually misbehave in the
 * field (a binder failure, a package that isn't there, per-frame lookup cost) is testable here
 * with no Android at all.
 */
class PlatformAppVersionsTest {

    private val doordash = "com.doordash.driverapp"

    @Test
    fun `a resolved version is returned and looked up only once`() {
        var lookups = 0
        val stats = PipelineStats()
        val versions = CachingPlatformAppVersions({ lookups++; "15.2.3" }, stats)

        repeat(100) { assertEquals("15.2.3", versions.versionName(doordash)) }

        assertEquals("one binder round-trip per package while fresh", 1, lookups)
    }

    @Test
    fun `a fresh version is cached and re-resolved at the TTL`() {
        var now = 0L
        var lookups = 0
        val versions = CachingPlatformAppVersions({ "7.${++lookups}" }, PipelineStats(), ttlMillis = 1000, clock = { now })

        assertEquals("7.1", versions.versionName(doordash))
        now = 999
        assertEquals("7.1", versions.versionName(doordash))
        assertEquals(1, lookups)
        now = 1000
        repeat(100) { assertEquals("7.2", versions.versionName(doordash)) }
        assertEquals(2, lookups)
    }

    @Test
    fun `a negative result expires after the default ten minutes`() {
        var now = 0L
        var lookups = 0
        val versions = CachingPlatformAppVersions({ if (++lookups == 1) null else "7.2" }, PipelineStats(), clock = { now })

        assertNull(versions.versionName(doordash))
        now = 599_999
        assertNull(versions.versionName(doordash))
        assertEquals(1, lookups)
        now = 600_000
        repeat(100) { assertEquals("7.2", versions.versionName(doordash)) }
        assertEquals(2, lookups)
    }

    @Test
    fun `a throwing lookup after expiry fails open and caches null`() {
        var now = 0L
        var lookups = 0
        val versions = CachingPlatformAppVersions(
            { if (++lookups == 1) "7.1" else throw IllegalStateException("DeadObjectException") },
            PipelineStats(), ttlMillis = 1000, clock = { now },
        )

        assertEquals("7.1", versions.versionName(doordash))
        now = 1000
        repeat(100) { assertNull(versions.versionName(doordash)) }
        assertEquals(2, lookups)
    }

    @Test
    fun `an unresolvable package is cached negatively - no per-frame lookup storm`() {
        var lookups = 0
        val versions = CachingPlatformAppVersions({ lookups++; null }, PipelineStats())

        repeat(100) { assertNull(versions.versionName("com.not.installed")) }

        assertEquals(1, lookups)
    }

    @Test
    fun `a throwing lookup fails open and is not retried per frame`() {
        var lookups = 0
        val versions = CachingPlatformAppVersions(
            { lookups++; throw IllegalStateException("DeadObjectException") },
            PipelineStats(),
        )

        repeat(50) { assertNull(versions.versionName(doordash)) }

        assertEquals(1, lookups)
    }

    @Test
    fun `a resolved version is recorded for the periodic summary line`() {
        val stats = PipelineStats()
        val versions = CachingPlatformAppVersions({ "15.2.3" }, stats)

        versions.versionName(doordash)

        val summary = stats.summary()
        assertTrue("expected the version in $summary", summary.contains("platformApps=$doordash@15.2.3"))
    }

    @Test
    fun `an unstamped process leaves the summary line unchanged`() {
        assertTrue(
            "no observed versions must add nothing to the summary",
            !PipelineStats().summary().contains("platformApps"),
        )
    }

    @Test
    fun `the cache is bounded but still answers past the cap`() {
        var lookups = 0
        val versions = CachingPlatformAppVersions({ pkg -> lookups++; "v-$pkg" }, PipelineStats())

        repeat(CachingPlatformAppVersions.MAX_CACHED_PACKAGES + 5) {
            assertEquals("v-pkg$it", versions.versionName("pkg$it"))
        }
        // Past the cap the answer is still correct; only the memoisation stops.
        assertEquals("v-pkg0", versions.versionName("pkg0"))

        assertEquals(CachingPlatformAppVersions.MAX_CACHED_PACKAGES + 5, lookups)
    }

    @Test
    fun `an empty package is never looked up`() {
        val versions = CachingPlatformAppVersions({ error("must not be called") }, PipelineStats())

        assertNull(versions.versionName(""))
    }
}
