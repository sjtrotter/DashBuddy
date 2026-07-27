package cloud.trotter.dashbuddy.test.util

import io.kotest.common.ExperimentalKotest
import io.kotest.property.PropTestConfig

/**
 * #878 — determinism control for the `:app` half of the #590 property/fuzz suite.
 *
 * **Mirror of `cloud.trotter.dashbuddy.core.pipeline.PropSeeds`, which is authoritative.**
 * Duplicated by choice: there is no test-source dependency edge from `:app`'s test
 * compilation to `:core:pipeline`'s, and standing up `testFixtures` for one 20-line
 * object costs more build surface than the copy costs. Change the `:core:pipeline` copy
 * first, then mirror it here.
 *
 * Rationale in full lives on that copy. In short: every property pins a seed so PR CI is
 * deterministic and a failure is a reproducible finding rather than a dice roll; bump a
 * seed deliberately to explore new samples; `-Ddashbuddy.propExplore=true` restores
 * unseeded exploration at [EXPLORE_FACTOR]× the sample count off the PR path.
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

    /** Pinned [seed] on PR CI (deterministic); unseeded under exploration. */
    // PropTestConfig's `constraints`/`maxDiscardPercentage` defaults are @ExperimentalKotest
    // in 5.9.1; we touch neither, but constructing the class needs the opt-in.
    @OptIn(ExperimentalKotest::class)
    fun config(seed: Long): PropTestConfig =
        if (exploring) PropTestConfig(seed = null) else PropTestConfig(seed = seed)
}
