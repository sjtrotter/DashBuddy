package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #624 (VET V2) corpus-validate for the recognized-frame customer-marker backstop.
 *
 * It mirrors the production capture flow — recognize the frame, apply that rule's
 * `redact`, THEN run [CustomerTextMarkers.firstUnredactedMarker] — over EVERY
 * committed, already-redacted snapshot, and asserts ZERO hits. The corpus is clean
 * by construction, so ANY hit is a false positive: this is what pins the marker set
 * (why "Heading to " — a STORE-name prefix on pickup-nav frames — is deliberately
 * excluded, VET V2) and proves the #624 rule/data fixes (dropoff_reminder,
 * pickup_verify_items) leave the corpus scrub-free.
 */
class CaptureBackstopCorpusTest {

    private val screenRuleset = TestRulesetFactory.screenRuleset

    @Test
    fun `the customer-marker backstop finds zero leaks across the redacted corpus`() {
        val base = File("src/test/resources/snapshots")
        val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP }
            ?.sortedBy { it.name } ?: emptyList()

        var scanned = 0
        for (dir in dirs) {
            for ((fn, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                // UNKNOWN frames route through SensitiveTextMarkers, not this backstop.
                val match = screenRuleset.matchFirst(node) ?: continue
                // Mirror captureScreen: recognized rule's redact first, then backstop scan.
                val redacted = screenRuleset.ruleById(match.ruleId)
                    ?.redact?.takeUnless { it.isEmpty() }
                    ?.apply(node)
                    ?: node
                val marker = CustomerTextMarkers.firstUnredactedMarker(redacted)
                assertNull(
                    "snapshots/${dir.name}/$fn leaked an un-redacted customer marker '$marker' " +
                        "(rule ${match.ruleId}) — a false positive on a clean corpus; fix the rule's " +
                        "redact or the marker set",
                    marker,
                )
                scanned++
            }
        }
        assertTrue("expected a non-empty corpus", scanned > 0)
    }

    companion object {
        private val SKIP = setOf("INBOX", "UNKNOWN", "SENSITIVE")
    }
}
