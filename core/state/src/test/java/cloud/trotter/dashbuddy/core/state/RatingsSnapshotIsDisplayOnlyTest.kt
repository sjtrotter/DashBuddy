package cloud.trotter.dashbuddy.core.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #962 — **no decision path may consume ratings data** (dev ruling 2026-07-30).
 *
 * `RatingsSnapshot` is an OPPORTUNISTIC capture: it only updates when the dasher
 * happens to open the platform's Ratings surface, so it may be null or
 * arbitrarily stale forever. Anything that branched on it would silently behave
 * differently for a dasher who browses their ratings than for one who doesn't.
 * The authoritative statement of the invariant is the `RatingsSnapshot` KDoc;
 * this is its cheap enforcement.
 *
 * The scan is a **source scan**, in the `TimberTagGuardTest` / `IcuRegexGuardTest`
 * doctrine — the property ("nothing here reads it") is about code that does *not*
 * exist, which no runtime test can assert.
 *
 * Scope: the decision surfaces — the whole `:core:state` reduction (steppers,
 * effect map, transition policy, recovery), the `:domain` offer evaluator, and
 * the `:domain` analytics folds. Read-side display surfaces (`RatingsScreen`,
 * `RatingsUiState`, the bubble idle card) are deliberately NOT scanned: showing
 * the dasher their own captured numbers is the whole point.
 *
 * The single permitted reference is [PlatformRegionStepper]'s `updateRatings`,
 * which STAMPS the snapshot from a parse. Stamping is not consuming — nothing
 * downstream of it reads a value back out.
 */
class RatingsSnapshotIsDisplayOnlyTest {

    /** The one file allowed to name ratings state: it writes it, never reads it. */
    private val stampingSite = "PlatformRegionStepper.kt"

    /**
     * Naming the type is one way to consume it; reaching it through
     * `PlatformRegion.ratings` is the other, and does not mention the type at
     * all. Both are scanned.
     */
    private val forbidden = listOf(
        Regex("""\bRatingsSnapshot\b"""),
        Regex("""\.ratings\b"""),
    )

    /**
     * The Gradle working dir is the MODULE dir (`core/state`), so the repo root is
     * two levels up; the plain and one-level forms are kept so the guard also runs
     * from an IDE runner or the repo root.
     */
    private fun resolve(relative: String): File =
        listOf(File("../../$relative"), File("../$relative"), File(relative))
            .firstOrNull { it.isDirectory }
            ?: error(
                "Cannot resolve '$relative' from ${File(".").absolutePath}. " +
                    "The guard must not pass vacuously.",
            )

    @Test
    fun `no decision path reads ratings data (#962)`() {
        val roots = listOf(
            "core/state/src/main",
            "domain/src/main/java/cloud/trotter/dashbuddy/domain/evaluation",
            "domain/src/main/java/cloud/trotter/dashbuddy/domain/analytics",
        ).map(::resolve)

        val offenders = mutableListOf<String>()
        var filesScanned = 0
        var sawStampingSite = false

        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                filesScanned++
                val isStampingSite = file.name == stampingSite
                file.readLines().forEachIndexed { i, line ->
                    if (forbidden.none { it.containsMatchIn(line) }) return@forEachIndexed
                    if (isStampingSite) {
                        sawStampingSite = true
                    } else {
                        offenders += "${file.path}:${i + 1}: ${line.trim()}"
                    }
                }
            }
        }

        assertTrue(
            "Ratings data is an OPPORTUNISTIC capture — it may be null or arbitrarily stale " +
                "forever, so no evaluator / stepper / effect / analytics-fold path may read it " +
                "(#962, dev ruling 2026-07-30; see the RatingsSnapshot KDoc). If you need this " +
                "signal for a decision, derive it from DashBuddy's OWN observed data instead.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )

        // Non-vacuity: a moved file or a broken path must fail, not silently pass.
        assertTrue("scan found no Kotlin sources — the guard would pass vacuously", filesScanned > 50)
        assertTrue(
            "the $stampingSite stamping site was not seen — the guard has drifted off its target",
            sawStampingSite,
        )
    }
}
