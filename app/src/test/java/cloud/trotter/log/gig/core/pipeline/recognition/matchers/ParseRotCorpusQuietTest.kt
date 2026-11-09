package cloud.trotter.log.gig.core.pipeline.recognition.matchers

import cloud.trotter.log.gig.core.pipeline.ParseRotTracker
import cloud.trotter.log.gig.core.pipeline.RotEdge
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1560 — the golden corpus opens no parse-rot incident.
 *
 * Walks every sorted golden fixture (the [GoldenSnapshotRegressionTest] walk, minus `SENSITIVE`) through
 * the live ruleset and a [ParseRotTracker], each fixture its own visit: frame time advances 60 s per
 * fixture and a separator frame 30 s after it confirms the visit. That is the strictest reading — every
 * fixture a separate sighting — so a rule whose healthy screens leave a required field null trips here.
 *
 * On a failure, do not tune the tracker's constants or exempt a rule: the list of tripped rules and the
 * fixtures that tripped them is the finding.
 */
class ParseRotCorpusQuietTest {

    private val skip = setOf("INBOX", "UNKNOWN", "SENSITIVE")

    @Test
    fun `the golden corpus trips no parse rot`() {
        val ruleset = TestRulesetFactory.screenRuleset
        val tracker = ParseRotTracker()
        val lastAt = mutableMapOf<String, Long>()
        val tripped = mutableListOf<String>()
        var t = 0L

        fun collect(edges: List<RotEdge>, where: String) {
            edges.filterIsInstance<RotEdge.Tripped>().forEach { tripped += "${it.cause.ruleId} (at $where)" }
        }

        val dirs = File("src/test/resources/snapshots")
            .listFiles { f -> f.isDirectory && f.name !in skip }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("the corpus walk found no folders", dirs.isNotEmpty())

        for (dir in dirs) {
            for ((fileName, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                val shortfalls = mutableListOf<ParseShortfall>()
                val result = ruleset.matchFirst(node, onParseShortfall = { shortfalls += it })
                // A test key: the platform prefix of the rule that spoke for the frame.
                val wire = (result?.ruleId ?: shortfalls.firstOrNull()?.ruleId)?.substringBefore('.') ?: continue

                t += 60_000L
                val where = "${dir.name}/$fileName"
                collect(tracker.record(wire, t, result?.ruleId, shortfalls), where)
                collect(tracker.record(wire, t + 30_000L, null, emptyList()), where)
                lastAt[wire] = t + 30_000L
            }
        }
        for ((wire, last) in lastAt) collect(tracker.sweep(wire, last + 60_000L), "end of $wire")

        assertTrue(
            "the golden corpus tripped parse rot — report these, do not tune or exempt:\n" +
                tripped.joinToString("\n"),
            tripped.isEmpty(),
        )
    }
}
