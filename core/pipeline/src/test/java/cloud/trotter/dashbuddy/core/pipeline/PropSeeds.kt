package cloud.trotter.dashbuddy.core.pipeline

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig

/**
 * #878 — determinism control for the #590 security property/fuzz suite.
 *
 * **Why.** `TransformFuzzFailClosedTest` failed ONCE on CI (PR #869's first run) on a
 * diff that does not touch `:core:pipeline`, and 25 consecutive local runs (10 000
 * samples) could not reproduce it. Because the property was unseeded and the failure
 * output was not retained, the counterexample is **gone** — the fuzzer found something
 * and we cannot see it. An un-diagnosable flake in a *security* property suite is the
 * worst outcome available: it trains everyone to re-run past real findings.
 *
 * **The fix.** Every property in the suite pins an explicit seed, so PR CI draws the
 * same samples on every run — a failure is a real, reproducible finding, never a dice
 * roll. Bump a site's seed **deliberately** (a one-line, reviewable diff) when you want
 * that property to explore a new region; do not bump it to make a red test go green.
 *
 * **Breadth is not lost.** Seeding narrows PR CI to one fixed sample set, so the
 * exploration that found the #869 counterexample has to live somewhere. It lives here:
 * running with `-Ddashbuddy.propExplore=true` flips EVERY property back to unseeded
 * generation at [EXPLORE_FACTOR]× the pinned sample count.
 *
 * ```
 * # PR-CI shape (default): pinned seeds, pinned sample counts, fully deterministic
 * ./gradlew :core:pipeline:testDebugUnitTest
 *
 * # Exploration shape (nightly / manual): unseeded, EXPLORE_FACTOR x the samples
 * ./gradlew :core:pipeline:testDebugUnitTest :app:testDebugUnitTest \
 *     -Ddashbuddy.propExplore=true
 * ```
 *
 * A failure in EITHER shape prints the shrunken counterexample and the line
 * `Repeat this test by using seed <N>` — in exploration mode that seed is the thing to
 * paste into the failing site's [config] call, which converts a one-off finding into a
 * permanent regression pin.
 *
 * Gradle passes `-D` to the daemon, not to the forked test worker; the root
 * `build.gradle.kts` forwards [EXPLORE_PROPERTY] explicitly.
 *
 * **Duplication, by choice.** An identical copy lives in `:app`'s test source set
 * (`cloud.trotter.dashbuddy.test.util.PropSeeds`) because there is no test-source
 * dependency edge between the two modules and wiring `testFixtures` for one 20-line
 * object is not worth the build-graph surface. THIS copy is authoritative — change it
 * first, then mirror.
 */
internal object PropSeeds {

    /** `-D` flag that swaps pinned determinism for unseeded exploration. */
    const val EXPLORE_PROPERTY = "dashbuddy.propExplore"

    /** Sample-count multiplier applied in exploration mode. */
    private const val EXPLORE_FACTOR = 10

    /** True when [EXPLORE_PROPERTY] is set to anything other than `false`/`0`/empty. */
    val exploring: Boolean = System.getProperty(EXPLORE_PROPERTY)
        ?.lowercase()
        ?.let { it.isNotEmpty() && it != "false" && it != "0" } ?: false

    /** Pinned sample count on PR CI; [EXPLORE_FACTOR]× that under exploration. */
    fun samples(pinned: Int): Int = if (exploring) pinned * EXPLORE_FACTOR else pinned

    /**
     * Pinned [seed] on PR CI (deterministic); unseeded under exploration.
     *
     * Seeds are literal and per-site so a failure names exactly one property, and so
     * re-seeding one property never silently re-rolls the others.
     */
    // PropTestConfig's `constraints`/`maxDiscardPercentage` defaults are @ExperimentalKotest
    // in 5.9.1; we touch neither, but constructing the class needs the opt-in.
    @OptIn(ExperimentalKotest::class)
    fun config(seed: Long): PropTestConfig =
        if (exploring) PropTestConfig(seed = null) else PropTestConfig(seed = seed)
}
