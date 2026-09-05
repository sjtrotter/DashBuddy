package cloud.trotter.dashbuddy.buildid

import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.DashBuddyApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1062 — every build identifies itself.
 *
 * The receipt: the 2026-09-05 field-data pull had to INFER which build the phone ran from the
 * ABSENCE of log lines, because `:app:installDebug` reported the same `versionName=0.230.0` for a
 * branch build as for the master build it replaced. The identity is now computed in
 * `app/build.gradle.kts` at configuration time; this test is the shape guard on the other side of
 * that boundary — the Gradle script's own logic is not reachable from a unit test, but its OUTPUT
 * (`BuildConfig`) is, and the output is what every consumer reads.
 *
 * It asserts a SHAPE, never a specific sha: the sha changes every commit, so pinning one would
 * make the test a chore. `nogit` is an accepted value — the git reads are fail-safe by design (a
 * source zip / a `.git`-less export must still build), and this test must not turn that fallback
 * into a build failure.
 */
class BuildIdentityTest {

    /** `<semver>+<8 lowercase hex|nogit>(.dirty)?` */
    private val versionNameShape =
        Regex("""^\d+\.\d+\.\d+\+(?:[0-9a-f]{8}|nogit)(?:\.dirty)?$""")

    private val shaShape = Regex("""^(?:[0-9a-f]{8}|nogit)$""")

    @Test
    fun `versionName carries the git sha`() {
        assertTrue(
            "versionName '${BuildConfig.VERSION_NAME}' must match <semver>+<sha>[.dirty]",
            versionNameShape.matches(BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun `GIT_SHA is an eight-hex sha or the fail-safe sentinel`() {
        assertTrue(
            "GIT_SHA '${BuildConfig.GIT_SHA}' must be 8 hex chars or 'nogit'",
            shaShape.matches(BuildConfig.GIT_SHA),
        )
    }

    /** The one place versionName and GIT_SHA could silently disagree is the Gradle script. */
    @Test
    fun `versionName and GIT_SHA name the same commit`() {
        assertTrue(
            "versionName '${BuildConfig.VERSION_NAME}' must embed GIT_SHA '${BuildConfig.GIT_SHA}'",
            BuildConfig.VERSION_NAME.substringAfter('+').removeSuffix(".dirty")
                == BuildConfig.GIT_SHA,
        )
    }

    /**
     * A plausibility floor, not a clock assertion: 2020-01-01. Catches a `0` / seconds-vs-millis
     * mix-up in the `buildConfigField` without making the test time-dependent.
     */
    @Test
    fun `BUILD_TIME_MS is a plausible epoch-millis instant`() {
        assertTrue(
            "BUILD_TIME_MS=${BuildConfig.BUILD_TIME_MS} looks wrong",
            BuildConfig.BUILD_TIME_MS > 1_577_836_800_000L,
        )
    }

    /** The startup line's timestamp is a machine string — ISO-8601 UTC, `Locale.ROOT`. */
    @Test
    fun `the startup line renders the build time as an ISO-8601 UTC instant`() {
        val rendered = DashBuddyApplication.buildTimeIso()

        assertTrue(
            "'$rendered' is not an ISO-8601 UTC instant",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$""").matches(rendered),
        )
        assertEquals(
            BuildConfig.BUILD_TIME_MS,
            java.time.Instant.parse(rendered).toEpochMilli(),
        )
    }
}
